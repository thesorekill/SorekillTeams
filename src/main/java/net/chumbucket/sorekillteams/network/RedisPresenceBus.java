/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.network;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

public final class RedisPresenceBus {

    private static final String ONLINE_KEY_PREFIX = "online:";

    // server + name are stored in the redis value.
    // using a rarely-used ASCII unit separator so names like "a|b" won't break parsing
    private static final char SEP = 0x1F;

    private final SorekillTeamsPlugin plugin;
    private final String originServer;

    private final String channel;
    private final String keyPrefix; // e.g. sorekillteams:online:<uuid>

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final int timeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread subThread;
    private JedisPubSub pubSub;

    private int heartbeatTaskId = -1;
    private int snapshotTaskId = -1;

    // ✅ Cached view for tab completion and other fast lookups
    private final Map<UUID, String> cachedNamesByUuid = new ConcurrentHashMap<>();

    public RedisPresenceBus(SorekillTeamsPlugin plugin, ConfigurationSection sec, String originServer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.originServer = (originServer == null || originServer.isBlank()) ? "default" : originServer.trim();

        if (sec == null) throw new IllegalArgumentException("redis section missing");

        this.host = sec.getString("host", "127.0.0.1");
        this.port = Math.max(1, sec.getInt("port", 6379));
        this.username = sec.getString("username", "");
        this.password = sec.getString("password", "");
        this.ssl = sec.getBoolean("use_ssl", false);
        this.timeoutMs = Math.max(1000, sec.getInt("timeout_ms", 5000));

        String prefix = sec.getString("channel_prefix", "sorekillteams");
        if (prefix == null || prefix.isBlank()) prefix = "sorekillteams";

        this.channel = prefix + ":presence";
        this.keyPrefix = prefix + ":" + ONLINE_KEY_PREFIX; // e.g. sorekillteams:online:<uuid>
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String ch, String message) {
                if (!running.get()) return;
                if (message == null || message.isBlank()) return;

                PresencePacket pkt = PresencePacket.decode(message);
                if (pkt == null) return;

                // ignore self-origin
                if (originServer.equalsIgnoreCase(pkt.originServer())) return;

                // ✅ update our local cache immediately (tab completion needs this)
                applyPacketToCache(pkt);

                // keep your existing hook for other behaviors
                Bukkit.getScheduler().runTask(plugin, () -> plugin.onRemotePresence(pkt));
            }
        };

        subThread = new Thread(() -> {
            while (running.get()) {
                try (Jedis jedis = newJedis()) {
                    jedis.subscribe(pubSub, channel);
                } catch (Throwable t) {
                    try { Thread.sleep(1000L); } catch (InterruptedException ignored) {}
                }
            }
        }, "SorekillTeams-RedisPresenceSub");

        subThread.setDaemon(true);
        subThread.start();

        startHeartbeatTask();
        startSnapshotTask(); // ✅ so new backends learn current online users

        plugin.getLogger().info("PresenceBus=Redis subscribed to '" + channel + "' as server='" + originServer + "'");
    }

    public void stop() {
        running.set(false);

        if (heartbeatTaskId >= 0) {
            try { Bukkit.getScheduler().cancelTask(heartbeatTaskId); } catch (Exception ignored) {}
            heartbeatTaskId = -1;
        }

        if (snapshotTaskId >= 0) {
            try { Bukkit.getScheduler().cancelTask(snapshotTaskId); } catch (Exception ignored) {}
            snapshotTaskId = -1;
        }

        try { if (pubSub != null) pubSub.unsubscribe(); } catch (Exception ignored) {}
        if (subThread != null) {
            try { subThread.interrupt(); } catch (Exception ignored) {}
            subThread = null;
        }
        pubSub = null;

        cachedNamesByUuid.clear();
    }

    public boolean isRunning() { return running.get(); }

    /**
     * Cached network online names for fast sync usage (tab completion).
     */
    public Collection<String> cachedOnlineNames() {
        // snapshot stable iteration
        Set<String> out = new HashSet<>();
        for (String n : cachedNamesByUuid.values()) {
            if (n != null && !n.isBlank()) out.add(n);
        }
        return out;
    }

    /**
     * Mark a player online:
     * ✅ Only publish ONLINE if they were NOT already online network-wide.
     * This prevents backend-switch spam (Velocity server changes).
     */
    public void markOnline(Player p) {
        if (!running.get() || p == null) return;

        UUID u = p.getUniqueId();
        if (u == null) return;

        final String playerName = safe(p.getName());

        // update local cache immediately
        if (!playerName.isBlank()) cachedNamesByUuid.put(u, playerName);

        int ttlSeconds = Math.max(10, plugin.getConfig().getInt("redis.presence_ttl_seconds", 25));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = newJedis()) {
                final String key = keyPrefix + u;

                boolean alreadyOnline = jedis.exists(key);

                // Always refresh/take ownership so future OFFLINE ownership checks are correct
                jedis.setex(key, ttlSeconds, encodeValue(originServer, playerName));

                // ✅ Edge-trigger: only publish ONLINE if key did not exist
                if (!alreadyOnline) {
                    jedis.publish(channel, new PresencePacket(
                            originServer,
                            PresencePacket.Type.ONLINE,
                            u,
                            playerName,
                            originServer,
                            System.currentTimeMillis()
                    ).encode());
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Mark a player offline, NO-FLICKER edition.
     *
     * ✅ Only delete/publish OFFLINE if this backend still "owns" the key.
     * ✅ Delay OFFLINE slightly and re-check key ownership.
     */
    public void markOffline(UUID uuid, String nameHint) {
        if (!running.get() || uuid == null) return;

        long delayTicks = Math.max(0L, plugin.getConfig().getLong("redis.presence_offline_delay_ticks", 15L));

        if (delayTicks <= 0L) {
            markOfflineAfterDelay(uuid, nameHint);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!running.get()) return;
            markOfflineAfterDelay(uuid, nameHint);
        }, delayTicks);
    }

    private void markOfflineAfterDelay(UUID uuid, String nameHint) {
        if (!running.get() || uuid == null) return;

        // optimistic cache removal (will be re-added if another backend owns it)
        cachedNamesByUuid.remove(uuid);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = newJedis()) {
                String key = keyPrefix + uuid;

                // If another backend already claimed this player, do NOT publish offline.
                String currentRaw = jedis.get(key);
                String currentServer = parseServer(currentRaw);
                if (currentServer != null && !currentServer.isBlank() && !currentServer.equalsIgnoreCase(originServer)) {
                    // another backend owns this session -> keep them online
                    String currentName = parseName(currentRaw);
                    if (currentName != null && !currentName.isBlank()) cachedNamesByUuid.put(uuid, currentName);
                    return;
                }

                jedis.del(key);

                jedis.publish(channel, new PresencePacket(
                        originServer,
                        PresencePacket.Type.OFFLINE,
                        uuid,
                        safe(nameHint),
                        originServer,
                        System.currentTimeMillis()
                ).encode());
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Network-wide online check (best effort).
     */
    public boolean isOnlineNetwork(UUID uuid) {
        if (!running.get() || uuid == null) return false;
        try (Jedis jedis = newJedis()) {
            return jedis.exists(keyPrefix + uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    public String serverFor(UUID uuid) {
        if (!running.get() || uuid == null) return null;
        try (Jedis jedis = newJedis()) {
            return parseServer(jedis.get(keyPrefix + uuid));
        } catch (Throwable t) {
            return null;
        }
    }

    private void startHeartbeatTask() {
        if (!running.get()) return;

        long periodTicks = Math.max(40L, plugin.getConfig().getLong("redis.presence_heartbeat_period_ticks", 200L));

        heartbeatTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!running.get()) return;

            int ttlSeconds = Math.max(10, plugin.getConfig().getInt("redis.presence_ttl_seconds", 25));

            try (Jedis jedis = newJedis()) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p == null) continue;
                    UUID u = p.getUniqueId();
                    if (u == null) continue;

                    String name = safe(p.getName());
                    if (!name.isBlank()) cachedNamesByUuid.put(u, name);

                    // refresh and keep ownership updated
                    jedis.setex(keyPrefix + u, ttlSeconds, encodeValue(originServer, name));
                }
            } catch (Throwable ignored) {}

        }, periodTicks, periodTicks).getTaskId();
    }

    /**
     * ✅ Periodically SCAN redis for online keys and hydrate the cache.
     *
     * This solves the “backend started later” problem:
     * - markOnline is edge-triggered (won't republish every heartbeat)
     * - without snapshots, a new backend wouldn't learn existing online names
     */
    private void startSnapshotTask() {
        if (!running.get()) return;

        long periodTicks = Math.max(60L, plugin.getConfig().getLong("redis.presence_snapshot_refresh_ticks", 200L));

        snapshotTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!running.get()) return;

            Map<UUID, String> snap = new HashMap<>();

            try (Jedis jedis = newJedis()) {
                String cursor = "0";
                ScanParams params = new ScanParams()
                        .match(keyPrefix + "*")
                        .count(200);

                // safety cap so huge redis doesn't stall
                int loops = 0;

                do {
                    ScanResult<String> res = jedis.scan(cursor, params);
                    cursor = res.getCursor();

                    for (String key : res.getResult()) {
                        if (key == null) continue;

                        // key format: <keyPrefix><uuid>
                        String idPart = key.substring(keyPrefix.length());
                        UUID uuid = safeUuid(idPart);
                        if (uuid == null) continue;

                        String raw = jedis.get(key);
                        String name = parseName(raw);
                        if (name != null && !name.isBlank()) {
                            snap.put(uuid, name);
                        }
                    }

                    loops++;
                    if (loops >= 10) break; // cap work per run
                } while (!"0".equals(cursor));
            } catch (Throwable ignored) {}

            if (!snap.isEmpty()) {
                // merge snap into cache, but also drop stale entries not present in snap
                cachedNamesByUuid.keySet().retainAll(snap.keySet());
                cachedNamesByUuid.putAll(snap);
            } else {
                // If redis unreachable, don't nuke cache; just keep what we have.
            }

        }, periodTicks, periodTicks).getTaskId();
    }

    private void applyPacketToCache(PresencePacket pkt) {
        if (pkt == null || pkt.playerUuid() == null) return;

        if (pkt.type() == PresencePacket.Type.ONLINE) {
            String n = safe(pkt.playerName());
            if (!n.isBlank()) cachedNamesByUuid.put(pkt.playerUuid(), n);
        } else if (pkt.type() == PresencePacket.Type.OFFLINE) {
            cachedNamesByUuid.remove(pkt.playerUuid());
        }
    }

    private static UUID safeUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); } catch (Exception e) { return null; }
    }

    private static String safe(String s) {
        return (s == null ? "" : s);
    }

    private static String encodeValue(String server, String name) {
        String s = (server == null ? "" : server);
        String n = (name == null ? "" : name);
        return s + SEP + n;
    }

    private static String parseServer(String raw) {
        if (raw == null) return null;
        int idx = raw.indexOf(SEP);
        if (idx < 0) return raw; // backward compat (old value was server only)
        return raw.substring(0, idx);
    }

    private static String parseName(String raw) {
        if (raw == null) return null;
        int idx = raw.indexOf(SEP);
        if (idx < 0) return ""; // old format had no name
        if (idx + 1 >= raw.length()) return "";
        return raw.substring(idx + 1);
    }

    private Jedis newJedis() {
        DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder()
                .ssl(ssl)
                .connectionTimeoutMillis(timeoutMs)
                .socketTimeoutMillis(timeoutMs);

        if (username != null && !username.isBlank()) b.user(username);
        if (password != null && !password.isBlank()) b.password(password);

        return new Jedis(new HostAndPort(host, port), b.build());
    }
}
