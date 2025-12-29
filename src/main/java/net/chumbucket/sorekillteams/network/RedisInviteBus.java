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
import java.util.concurrent.atomic.AtomicBoolean;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class RedisInviteBus {

    private final SorekillTeamsPlugin plugin;
    private final String originServer;
    private final String channel;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final int timeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread subThread;
    private JedisPubSub pubSub;

    public RedisInviteBus(SorekillTeamsPlugin plugin, ConfigurationSection sec, String originServer) {
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
        this.channel = prefix + ":invites";
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String ch, String message) {
                if (!running.get()) return;
                if (message == null || message.isBlank()) return;

                InvitePacket pkt = InvitePacket.decode(message);
                if (pkt == null) return;

                if (originServer.equalsIgnoreCase(pkt.originServer())) return;

                Bukkit.getScheduler().runTask(plugin, () -> plugin.onRemoteInviteEvent(pkt));
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
        }, "SorekillTeams-RedisInvitesSub");

        subThread.setDaemon(true);
        subThread.start();

        plugin.getLogger().info("InviteBus=Redis subscribed to '" + channel + "' as server='" + originServer + "'");
    }

    public void stop() {
        running.set(false);
        try { if (pubSub != null) pubSub.unsubscribe(); } catch (Exception ignored) {}
        if (subThread != null) {
            try { subThread.interrupt(); } catch (Exception ignored) {}
            subThread = null;
        }
        pubSub = null;
    }

    public boolean isRunning() { return running.get(); }

    public void publish(InvitePacket pkt) {
        if (pkt == null) return;
        if (!running.get()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = newJedis()) {
                jedis.publish(channel, pkt.encode());
            } catch (Throwable ignored) {}
        });
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
