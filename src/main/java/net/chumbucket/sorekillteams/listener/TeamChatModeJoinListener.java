/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.listener;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.network.RedisTeamChatBus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public final class TeamChatModeJoinListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public TeamChatModeJoinListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("chat.toggle_enabled", true)) return;

        final Player p = event.getPlayer();
        final UUID uuid = p.getUniqueId();

        final var bus = plugin.teamChatBus();
        final boolean def = plugin.getConfig().getBoolean("chat.default_on_join", false);

        if (!(bus instanceof RedisTeamChatBus r)) {
            // No redis: just apply default
            try { plugin.teams().setTeamChatEnabled(uuid, def); } catch (Exception ignored) {}
            return;
        }

        // Redis GET async, apply state on main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Boolean stored = r.getTeamChatMode(uuid);
            final boolean desired = (stored != null) ? stored : def;

            Bukkit.getScheduler().runTask(plugin, () -> {
                try { plugin.teams().setTeamChatEnabled(uuid, desired); } catch (Exception ignored) {}
            });
        });
    }
}
