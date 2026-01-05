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
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RedisInviteToggleSnapshot {

    private RedisInviteToggleSnapshot() {}

    public static void loadAsync(SorekillTeamsPlugin plugin, ConfigurationSection sec) {
        if (plugin == null || sec == null) return;
        if (!(plugin.teams() instanceof SimpleTeamService simple)) return;

        String prefix = sec.getString("channel_prefix", "sorekillteams");
        if (prefix == null || prefix.isBlank()) prefix = "sorekillteams";
        String key = prefix + ":invites_disabled";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> raw;
            try (var jedis = new redis.clients.jedis.Jedis(
                    new redis.clients.jedis.HostAndPort(
                            sec.getString("host", "127.0.0.1"),
                            Math.max(1, sec.getInt("port", 6379))
                    ),
                    redis.clients.jedis.DefaultJedisClientConfig.builder()
                            .ssl(sec.getBoolean("use_ssl", false))
                            .connectionTimeoutMillis(Math.max(1000, sec.getInt("timeout_ms", 5000)))
                            .socketTimeoutMillis(Math.max(1000, sec.getInt("timeout_ms", 5000)))
                            .user(sec.getString("username", ""))
                            .password(sec.getString("password", ""))
                            .build()
            )) {
                raw = jedis.smembers(key);
            } catch (Throwable t) {
                raw = null;
            }

            if (raw == null || raw.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> simple.applyInvitesDisabledSnapshot(List.of()));
                return;
            }

            ArrayList<UUID> uuids = new ArrayList<>(raw.size());
            for (String s : raw) {
                if (s == null || s.isBlank()) continue;
                try { uuids.add(UUID.fromString(s.trim())); } catch (Exception ignored) {}
            }

            Bukkit.getScheduler().runTask(plugin, () -> simple.applyInvitesDisabledSnapshot(uuids));
        });
    }
}
