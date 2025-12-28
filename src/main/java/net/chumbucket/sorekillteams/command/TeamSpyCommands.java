/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.command;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

import static net.chumbucket.sorekillteams.util.CommandUtil.joinArgsAfter;

public final class TeamSpyCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    private static final String SPY_PERMISSION = "sorekillteams.spy";

    public TeamSpyCommands(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        if (!sub.equals("spy")) return false;

        if (!p.hasPermission(SPY_PERMISSION)) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        if (!plugin.getConfig().getBoolean("chat.spy.enabled", true)) {
            plugin.msg().send(p, "team_spy_disabled");
            return true;
        }

        if (args.length < 2) {
            plugin.msg().send(p, "team_spy_usage");
            return true;
        }

        String arg1 = args[1] == null ? "" : args[1].trim();

        if (arg1.equalsIgnoreCase("list")) {
            Collection<Team> spied = plugin.teams().getSpiedTeams(p.getUniqueId());
            if (spied == null || spied.isEmpty()) {
                plugin.msg().send(p, "team_spy_list_empty");
                return true;
            }

            String joined = spied.stream()
                    .filter(Objects::nonNull)
                    .map(Team::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(Msg.color("&7, &c")));

            plugin.msg().send(p, "team_spy_list",
                    "{teams}", Msg.color("&c" + joined)
            );
            return true;
        }

        if (arg1.equalsIgnoreCase("off") || arg1.equalsIgnoreCase("clear")) {
            plugin.teams().clearSpy(p.getUniqueId());
            plugin.msg().send(p, "team_spy_cleared");
            return true;
        }

        String teamNameRaw = joinArgsAfter(args, 0);
        if (teamNameRaw.isBlank()) {
            plugin.msg().send(p, "team_spy_usage");
            return true;
        }

        Team team = plugin.teams().getTeamByName(teamNameRaw).orElse(null);
        if (team == null) {
            plugin.msg().send(p, "team_spy_team_not_found",
                    "{team}", teamNameRaw
            );
            return true;
        }

        boolean enabled = plugin.teams().toggleSpy(p.getUniqueId(), team.getId());
        if (enabled) {
            plugin.msg().send(p, "team_spy_on",
                    "{team}", Msg.color(team.getName())
            );
        } else {
            plugin.msg().send(p, "team_spy_off",
                    "{team}", Msg.color(team.getName())
            );
        }

        if (debug) {
            plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " spy toggled team=" + team.getName() + " -> " + (enabled ? "ON" : "OFF"));
        }
        return true;
    }
}
