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
import net.chumbucket.sorekillteams.menu.CreateTeamFlow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class CreateTeamFlowListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public CreateTeamFlowListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;

        if (!CreateTeamFlow.isAwaiting(p.getUniqueId())) return;

        // consume the chat message (don't send to global chat)
        e.setCancelled(true);

        String msg = e.getMessage();

        // CreateTeamFlow touches team service + messages; do it on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            CreateTeamFlow.handle(plugin, p, msg);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        CreateTeamFlow.cancel(p.getUniqueId());
    }
}
