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
import net.chumbucket.sorekillteams.service.TeamServiceException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.UUID;

public final class TeamChatListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public TeamChatListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;

        // Async thread context
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();

        // Only intercept if they are in team chat mode
        if (plugin.teams() == null || !plugin.teams().isTeamChatEnabled(uuid)) return;

        final boolean debug = plugin.getConfig().getBoolean("chat.debug", false);

        final String raw = event.getMessage();
        if (raw == null) return;

        final String msg = raw.trim();
        if (msg.isEmpty()) return;

        // Cancel normal chat
        event.setCancelled(true);

        if (debug) {
            plugin.getLogger().info("[TC-DBG] intercept " + name + " len=" + msg.length());
        }

        // IMPORTANT: schedule on main thread; do NOT use Bukkit APIs that require main thread here
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                final Player live = Bukkit.getPlayer(uuid);
                if (live == null || !live.isOnline()) {
                    if (debug) plugin.getLogger().info("[TC-DBG] abort send (offline) " + name);
                    return;
                }

                // They might have toggled team chat off between intercept and now
                if (!plugin.teams().isTeamChatEnabled(uuid)) {
                    if (debug) plugin.getLogger().info("[TC-DBG] abort send (toggle off) " + name);
                    return;
                }

                // Defensive: if toggle is ON but player has no team, turn it off to avoid “stuck intercept”
                if (plugin.teams().getTeamByPlayer(uuid).isEmpty()) {
                    try {
                        plugin.teams().setTeamChatEnabled(uuid, false);
                    } catch (Exception ignored) {}
                    if (debug) plugin.getLogger().info("[TC-DBG] auto-disabled toggle (no team) for " + name);
                    return;
                }

                plugin.teams().sendTeamChat(live, msg);

                if (debug) {
                    plugin.getLogger().info("[TC-DBG] sent teamchat from " + name);
                }

            } catch (TeamServiceException ex) {
                String code = (ex.code() == null ? "null" : ex.code().name());
                if (debug) {
                    plugin.getLogger().warning("[TC-DBG] TeamChatListener service error for " + name + ": " + code);
                } else {
                    plugin.getLogger().warning("TeamChatListener service error for " + name + ": " + code);
                }
            } catch (Exception ex) {
                plugin.getLogger().severe("TeamChatListener error for " + name + ": " +
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        });
    }
}
