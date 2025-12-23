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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class TeamChatListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public TeamChatListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;

        final Player player = event.getPlayer();

        // Only intercept if they are in team chat mode
        if (!plugin.teams().isTeamChatEnabled(player.getUniqueId())) return;

        // Cancel normal chat
        event.setCancelled(true);

        final String msgRaw = event.getMessage();
        if (msgRaw == null) return;

        final String msg = msgRaw.trim();
        if (msg.isEmpty()) return;

        // IMPORTANT: AsyncPlayerChatEvent runs off-thread. Schedule team chat send on main thread.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                plugin.teams().sendTeamChat(player, msg);
            } catch (TeamServiceException ex) {
                // Keep silent in chat (no spam), but log once for debugging.
                plugin.getLogger().warning("TeamChatListener service error for " + player.getName() + ": " +
                        (ex.code() == null ? "null" : ex.code().name()));
            } catch (Exception ex) {
                plugin.getLogger().severe("TeamChatListener error for " + player.getName() + ": " +
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        });
    }
}