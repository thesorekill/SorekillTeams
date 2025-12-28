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
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.service.TeamServiceException;
import net.chumbucket.sorekillteams.util.CommandErrors;
import net.chumbucket.sorekillteams.util.Msg;
import net.chumbucket.sorekillteams.util.TeamNameValidator;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CreateTeamFlow {

    private static final Map<UUID, Boolean> awaiting = new ConcurrentHashMap<>();

    private CreateTeamFlow() {}

    public static void begin(SorekillTeamsPlugin plugin, Player p) {
        if (plugin == null || p == null) return;

        if (!p.hasPermission("sorekillteams.create")) {
            plugin.msg().send(p, "no_permission");
            return;
        }

        if (plugin.teams().getTeamByPlayer(p.getUniqueId()).isPresent()) {
            plugin.msg().send(p, "team_already_in_team");
            return;
        }

        awaiting.put(p.getUniqueId(), Boolean.TRUE);

        // ✅ Uses messages.yml + prefix
        plugin.msg().send(p, "team_create_flow_prompt");
        plugin.msg().send(p, "team_create_flow_cancel_hint");
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
            plugin.msg().send(p, "team_create_flow_cancelled");
            return;
        }

        // Validate using the same rules as /team create
        TeamNameValidator validator = new TeamNameValidator(plugin);
        TeamNameValidator.Validation v = validator.validate(msg);

        if (!v.ok()) {
            // send reason and keep flow active
            plugin.msg().send(p, v.reasonKey());
            return;
        }

        try {
            Team t = plugin.teams().createTeam(uuid, v.plainName());

            cancel(uuid);

            plugin.msg().send(p, "team_created",
                    "{team}", Msg.color(t.getName())
            );

            if (plugin.menuRouter() != null) {
                plugin.menuRouter().open(p, "team_info");
            }

        } catch (TeamServiceException ex) {
            // Proper TeamError -> messages.yml mapping
            CommandErrors.send(p, plugin, ex);

            // keep flow active so they can try again
            awaiting.put(uuid, Boolean.TRUE);

        } catch (Exception ex) {
            plugin.msg().send(p, "team_create_flow_failed");

            // keep flow active so they can try again
            awaiting.put(uuid, Boolean.TRUE);
        }
    }
}
