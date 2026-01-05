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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class RedisTeamChatBus implements TeamChatBus {

    private final SorekillTeamsPlugin plugin;
    private final String originServer;
    private final String channel;

    // ✅ KV persistence for toggle mode
    private final String modeKeyPrefix;
    private final int modeTtlSeconds;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final int timeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread subThread;
    private JedisPubSub pubSub;

    public RedisTeamChatBus(SorekillTeamsPlugin plugin, ConfigurationSection sec, String originServer) {
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
        this.channel = prefix + ":teamchat";

        // ✅ Toggle mode KV key prefix + TTL
        this.modeKeyPrefix = prefix + ":teamchat:mode:";
        // default 30 days, 0 = never expire
        this.modeTtlSeconds = Math.max(0, sec.getInt("teamchat_mode_ttl_seconds", 60 * 60 * 24 * 30));
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String ch, String message) {
                if (!running.get()) return;
                if (message == null || message.isBlank()) return;

                TeamChatPacket pkt = TeamChatPacket.decode(message);
                if (pkt == null) return;

                // ignore our own publishes
                if (originServer.equalsIgnoreCase(pkt.originServer())) return;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    // best-effort convergence (safe no-op in yaml mode)
                    try {
                        plugin.ensureTeamsSnapshotFreshFromSql();
                        // ✅ makes remote chat reliable immediately after join/leave/kick events
                        if (pkt.senderUuid() != null) plugin.ensureTeamFreshFromSql(pkt.senderUuid());
                    } catch (Throwable ignored) {}

                    plugin.broadcastRemoteTeamChat(
                            pkt.teamId(),
                            pkt.senderUuid(),
                            pkt.senderName(),
                            pkt.coloredMessage()
                    );
                });
            }
        };

        subThread = new Thread(() -> {
            while (running.get()) {
                try (Jedis jedis = newJedis()) {
                    jedis.subscribe(pubSub, channel);
                } catch (Throwable t) {
                    // reconnect backoff
                    try { Thread.sleep(1000L); } catch (InterruptedException ignored) {}
                }
            }
        }, "SorekillTeams-RedisSub");

        subThread.setDaemon(true);
        subThread.start();

        plugin.getLogger().info("TeamChatBus=Redis subscribed to '" + channel + "' as server='" + originServer + "'");
    }

    @Override
    public void stop() {
        running.set(false);

        try {
            if (pubSub != null) pubSub.unsubscribe();
        } catch (Exception ignored) {}

        if (subThread != null) {
            try { subThread.interrupt(); } catch (Exception ignored) {}
            subThread = null;
        }

        pubSub = null;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void publish(TeamChatPacket packet) {
        if (packet == null) return;
        if (!running.get()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = newJedis()) {
                jedis.publish(channel, packet.encode());
            } catch (Throwable ignored) {
                // quiet: local send already happened
            }
        });
    }

    // ------------------------------------------------------------------------
    // ✅ TeamChat toggle persistence (KV)
    // ------------------------------------------------------------------------

    public void setTeamChatMode(UUID playerUuid, boolean enabled) {
        if (playerUuid == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = newJedis()) {
                final String key = modeKeyPrefix + playerUuid;
                final String val = enabled ? "1" : "0";

                if (modeTtlSeconds > 0) {
                    jedis.setex(key, modeTtlSeconds, val);
                } else {
                    jedis.set(key, val);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * @return Boolean.TRUE/FALSE if stored, otherwise null if no preference stored (use default_on_join).
     */
    public Boolean getTeamChatMode(UUID playerUuid) {
        if (playerUuid == null) return null;

        try (Jedis jedis = newJedis()) {
            final String v = jedis.get(modeKeyPrefix + playerUuid);
            if (v == null) return null;

            final String s = v.trim();
            if (s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on")) return Boolean.TRUE;
            if (s.equals("0") || s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off")) return Boolean.FALSE;

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Jedis newJedis() {
        DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder()
                .ssl(ssl)
                .connectionTimeoutMillis(timeoutMs)
                .socketTimeoutMillis(timeoutMs);

        if (username != null && !username.isBlank()) {
            b.user(username);
        }
        if (password != null && !password.isBlank()) {
            b.password(password);
        }

        return new Jedis(new HostAndPort(host, port), b.build());
    }
}
