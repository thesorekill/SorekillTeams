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
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class TeamOnlineStatusListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public TeamOnlineStatusListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        if (p == null) return;

        // ✅ Network-wide presence (Velocity-wide online status)
        if (plugin.isPresenceNetworkEnabled()) {
            plugin.markPresenceOnline(p);
            return;
        }

        // Fallback: original local-only messages
        Team team = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        if (team == null) return;

        broadcastToOnlineTeammates(team, p.getUniqueId(),
                "team_member_online",
                "{player}", p.getName(),
                "{team}", Msg.color(team.getName())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        final Player p = event.getPlayer();
        if (p == null) return;

        // ✅ Network-wide presence (Velocity-wide online status)
        if (plugin.isPresenceNetworkEnabled()) {
            plugin.markPresenceOffline(p.getUniqueId(), p.getName());
            return;
        }

        // Fallback: original local-only messages
        Team team = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        if (team == null) return;

        broadcastToOnlineTeammates(team, p.getUniqueId(),
                "team_member_offline",
                "{player}", p.getName(),
                "{team}", Msg.color(team.getName())
        );
    }

    private void broadcastToOnlineTeammates(Team team,
                                            UUID actor,
                                            String messageKey,
                                            String... pairs) {
        if (team == null || actor == null) return;

        Set<UUID> members = new HashSet<>(team.getMembers());

        for (UUID memberId : members) {
            if (memberId == null) continue;
            if (memberId.equals(actor)) continue;

            Player online = Bukkit.getPlayer(memberId);
            if (online == null || !online.isOnline()) continue;

            plugin.msg().send(online, messageKey, pairs);
        }
    }
}
