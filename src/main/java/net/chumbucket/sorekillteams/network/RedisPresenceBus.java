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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class RedisPresenceBus {

    private static final String ONLINE_KEY_PREFIX = "online:";

    private final SorekillTeamsPlugin plugin;
    private final String originServer;

    private final String channel;
    private final String keyPrefix; // includes channel_prefix so clusters don't collide

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

        plugin.getLogger().info("PresenceBus=Redis subscribed to '" + channel + "' as server='" + originServer + "'");
    }

    public void stop() {
        running.set(false);

        if (heartbeatTaskId >= 0) {
            try { Bukkit.getScheduler().cancelTask(heartbeatTaskId); } catch (Exception ignored) {}
            heartbeatTaskId = -1;
        }

        try { if (pubSub != null) pubSub.unsubscribe(); } catch (Exception ignored) {}
        if (subThread != null) {
            try { subThread.interrupt(); } catch (Exception ignored) {}
            subThread = null;
        }
        pubSub = null;
    }

    public boolean isRunning() { return running.get(); }

    /**
     * Mark a player online:
     * ✅ Only publish ONLINE if they were NOT already online network-wide.
     * This prevents backend-switch spam (Velocity server changes).
     */
    public void markOnline(Player p) {
        if (!running.get() || p == null) return;

        UUID u = p.getUniqueId();
        if (u == null) return;

        int ttlSeconds = Math.max(10, plugin.getConfig().getInt("redis.presence_ttl_seconds", 25));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = newJedis()) {
                final String key = keyPrefix + u;

                boolean alreadyOnline = jedis.exists(key);

                // Always refresh/take ownership so future OFFLINE ownership checks are correct
                jedis.setex(key, ttlSeconds, originServer);

                // ✅ Edge-trigger: only publish ONLINE if key did not exist
                if (!alreadyOnline) {
                    jedis.publish(channel, new PresencePacket(
                            originServer,
                            PresencePacket.Type.ONLINE,
                            u,
                            safe(p.getName()),
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

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = newJedis()) {
                String key = keyPrefix + uuid;

                // If another backend already claimed this player, do NOT publish offline.
                String current = jedis.get(key);
                if (current != null && !current.isBlank() && !current.equalsIgnoreCase(originServer)) {
                    return; // switched servers, keep them online
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
            return jedis.get(keyPrefix + uuid);
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

                    // refresh and keep ownership updated
                    jedis.setex(keyPrefix + u, ttlSeconds, originServer);
                }
            } catch (Throwable ignored) {}

        }, periodTicks, periodTicks).getTaskId();
    }

    private static String safe(String s) {
        return (s == null ? "" : s);
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
