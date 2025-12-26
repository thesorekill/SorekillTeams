/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.menu;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.service.TeamServiceException;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CreateTeamFlow {

    private static final Map<UUID, Boolean> awaiting = new ConcurrentHashMap<>();

    private CreateTeamFlow() {}

    public static void begin(SorekillTeamsPlugin plugin, Player p) {
        if (plugin == null || p == null) return;

        if (plugin.teams().getTeamByPlayer(p.getUniqueId()).isPresent()) {
            plugin.msg().send(p, "team_already_in_team");
            return;
        }

        awaiting.put(p.getUniqueId(), Boolean.TRUE);

        plugin.msg().sendRaw(p, "&8[&bTeams&8] &7Type your team name in chat.");
        plugin.msg().sendRaw(p, "&8[&bTeams&8] &7Type &cCancel&7 to stop.");
    }

    public static boolean isAwaiting(UUID uuid) {
        return uuid != null && awaiting.containsKey(uuid);
    }

    public static void cancel(UUID uuid) {
        if (uuid != null) awaiting.remove(uuid);
    }

    public static void handle(SorekillTeamsPlugin plugin, Player p, String message) {
        if (plugin == null || p == null) return;

        UUID uuid = p.getUniqueId();
        if (!isAwaiting(uuid)) return;

        String msg = message == null ? "" : message.trim();
        if (msg.isEmpty()) return;

        if (msg.equalsIgnoreCase("cancel")) {
            cancel(uuid);
            plugin.msg().sendRaw(p, "&8[&bTeams&8] &7Cancelled.");
            return;
        }

        cancel(uuid);

        try {
            plugin.teams().createTeam(uuid, msg);
            // If you already have a message key for create success, use it here.
            plugin.msg().sendRaw(p, "&8[&bTeams&8] &aCreated team &f" + msg + "&a.");
        } catch (TeamServiceException ex) {
            // If your TeamServiceException stores a message-key, you can wire that here later.
            plugin.msg().sendRaw(p, "&8[&bTeams&8] &c" + ex.getMessage());
        } catch (Exception ex) {
            plugin.msg().sendRaw(p, "&8[&bTeams&8] &cFailed to create team.");
        }
    }
}
