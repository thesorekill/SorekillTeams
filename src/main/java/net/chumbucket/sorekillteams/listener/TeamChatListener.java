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
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.service.TeamServiceException;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TeamChatListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    // ✅ Hot-reload / double-registration guard: prevents duplicate intercepts
    // for the same player while a previous chat event is still being handled.
    private static final java.util.Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    public TeamChatListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;

        final Player player = event.getPlayer();
        if (player == null) return;

        final UUID uuid = player.getUniqueId();
        final String name = player.getName();

        if (plugin.teams() == null || !plugin.teams().isTeamChatEnabled(uuid)) return;

        final boolean debug = plugin.getConfig().getBoolean("chat.debug", false);

        final String raw = event.getMessage();
        if (raw == null) return;

        final String msg = raw.trim();
        if (msg.isEmpty()) return;

        // We will handle sending ourselves
        event.setCancelled(true);

        // ✅ If we’re already handling a teamchat for this player, drop this duplicate
        if (!IN_FLIGHT.add(uuid)) {
            if (debug) plugin.getLogger().info("[TC-DBG] drop duplicate in-flight for " + name);
            return;
        }

        if (debug) plugin.getLogger().info("[TC-DBG] intercept " + name + " len=" + msg.length());

        // hop to main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                final Player live = Bukkit.getPlayer(uuid);
                if (live == null || !live.isOnline()) {
                    if (debug) plugin.getLogger().info("[TC-DBG] abort send (offline) " + name);
                    return;
                }

                if (!plugin.teams().isTeamChatEnabled(uuid)) {
                    if (debug) plugin.getLogger().info("[TC-DBG] abort send (toggle off) " + name);
                    return;
                }

                Team team = plugin.teams().getTeamByPlayer(uuid).orElse(null);
                if (team == null) {
                    try { plugin.teams().setTeamChatEnabled(uuid, false); } catch (Exception ignored) {}
                    if (debug) plugin.getLogger().info("[TC-DBG] auto-disabled toggle (no team) for " + name);
                    return;
                }

                // ✅ Single source of truth:
                // sendTeamChat handles:
                // - local broadcast
                // - spy broadcast
                // - optional redis publish
                plugin.teams().sendTeamChat(live, msg);

                if (debug) plugin.getLogger().info("[TC-DBG] sent teamchat from " + name);

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
            } finally {
                IN_FLIGHT.remove(uuid);
            }
        });
    }
}
