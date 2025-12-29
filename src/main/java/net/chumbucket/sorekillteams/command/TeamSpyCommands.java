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
import java.util.Locale;
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
        if (!sub.equalsIgnoreCase("spy")) return false;

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

        // /team spy list
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

        // /team spy clear  (and legacy: /team spy off)
        if (arg1.equalsIgnoreCase("off") || arg1.equalsIgnoreCase("clear")) {
            plugin.teams().clearSpy(p.getUniqueId());
            plugin.msg().send(p, "team_spy_cleared");
            return true;
        }

        // Optional explicit set: /team spy <team> on|off
        // (still supports plain toggle: /team spy <team>)
        String mode = null;
        if (args.length >= 3 && args[2] != null) {
            String m = args[2].trim().toLowerCase(Locale.ROOT);
            if (m.equals("on") || m.equals("enable") || m.equals("true")) mode = "on";
            else if (m.equals("off") || m.equals("disable") || m.equals("false")) mode = "off";
        }

        // ✅ Team name is everything after "spy" (index 1), but if a mode is present, exclude it
        final String teamNameRaw;
        if (mode != null) {
            // join args after index 1 but stop before last arg (mode)
            teamNameRaw = joinArgsBetween(args, 1, args.length - 2);
        } else {
            teamNameRaw = joinArgsAfter(args, 1);
        }

        if (teamNameRaw == null || teamNameRaw.isBlank()) {
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

        boolean enabled;
        if (mode == null) {
            enabled = plugin.teams().toggleSpy(p.getUniqueId(), team.getId());
        } else {
            boolean currently = plugin.teams().getSpiedTeams(p.getUniqueId()).stream()
                    .anyMatch(t -> t != null && team.getId().equals(t.getId()));

            boolean wantOn = mode.equals("on");
            if (wantOn == currently) {
                enabled = currently;
            } else {
                enabled = plugin.teams().toggleSpy(p.getUniqueId(), team.getId());
            }
        }

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
            plugin.getLogger().info("[TEAM-DBG] " + p.getName()
                    + " spy toggled team=" + team.getName()
                    + " -> " + (enabled ? "ON" : "OFF"));
        }
        return true;
    }

    /**
     * Join args from (afterIndex+1) through endIndexInclusive as a single space-separated string.
     * Example: joinArgsBetween(args, 1, 2) joins args[2]..args[2]
     */
    private static String joinArgsBetween(String[] args, int afterIndex, int endIndexInclusive) {
        if (args == null) return "";
        int start = afterIndex + 1;
        int end = Math.min(endIndexInclusive, args.length - 1);
        if (start > end) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            String a = args[i];
            if (a == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
        }
        return sb.toString().trim();
    }
}
