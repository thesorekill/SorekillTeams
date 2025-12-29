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
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class AdminCommandTabCompleter implements TabCompleter {

    private final SorekillTeamsPlugin plugin;

    private static final String PERM_ADMIN = "sorekillteams.admin";
    private static final String PERM_RELOAD = "sorekillteams.admin.reload";
    private static final String PERM_VERSION = "sorekillteams.admin.version";
    private static final String PERM_DISBAND = "sorekillteams.admin.disband";
    private static final String PERM_SETOWNER = "sorekillteams.admin.setowner";
    private static final String PERM_KICK = "sorekillteams.admin.kick";
    private static final String PERM_INFO = "sorekillteams.admin.info";

    public AdminCommandTabCompleter(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (sender == null) return List.of();
        if (!sender.hasPermission(PERM_ADMIN)) return List.of();
        if (args == null) return List.of();

        // /sorekillteams <sub>
        if (args.length == 1) {
            return partial(args[0], subcommandsFor(sender));
        }

        final String sub = lower(args[0]);

        // /sorekillteams disband <team...>
        if (subIs(sub, "disband")) {
            if (!sender.hasPermission(PERM_DISBAND)) return List.of();
            String joined = joinFrom(args, 1);
            return partial(joined, allTeamNames());
        }

        // /sorekillteams info <team...>
        if (subIs(sub, "info")) {
            if (!sender.hasPermission(PERM_INFO)) return List.of();
            String joined = joinFrom(args, 1);
            return partial(joined, allTeamNames());
        }

        // /sorekillteams kick <playerOnline|uuid>
        if (subIs(sub, "kick")) {
            if (!sender.hasPermission(PERM_KICK)) return List.of();
            if (args.length == 2) {
                return partial(args[1], onlinePlayerNames());
            }
            return List.of();
        }

        // /sorekillteams setowner <team...> <playerOnline|uuid>
        // Multi-word team name: last arg is the player token.
        if (subIs(sub, "setowner")) {
            if (!sender.hasPermission(PERM_SETOWNER)) return List.of();

            // if they haven't typed the final token yet, help with team names
            if (args.length == 2) {
                // user is starting team name
                return partial(args[1], allTeamNames());
            }

            // If they are typing the final token (player), suggest online names
            // BUT: because team names can be multi-word, the "player token" is always the LAST arg
            // If they are still typing the team name, completions will still work (it matches last token only).
            String last = args[args.length - 1];
            if (args.length >= 3) {
                // Suggest both team names (for ongoing team typing) and player names (for last token)
                // This feels nice while they build multi-word team names.
                List<String> out = new ArrayList<>();
                out.addAll(onlinePlayerNames());
                // Also include teams so "My Team" continues to complete while typing
                out.addAll(allTeamNames());
                return partial(last, out);
            }

            return List.of();
        }

        // reload/version don't need arg completions
        return List.of();
    }

    private List<String> subcommandsFor(CommandSender sender) {
        List<String> subs = new ArrayList<>();

        if (sender.hasPermission(PERM_RELOAD)) subs.add("reload");
        if (sender.hasPermission(PERM_VERSION)) subs.add("version");
        if (sender.hasPermission(PERM_DISBAND)) subs.add("disband");
        if (sender.hasPermission(PERM_SETOWNER)) subs.add("setowner");
        if (sender.hasPermission(PERM_KICK)) subs.add("kick");
        if (sender.hasPermission(PERM_INFO)) subs.add("info");

        return subs.stream()
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .filter(Objects::nonNull)
                .map(Player::getName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private List<String> allTeamNames() {
        try {
            // If you have a direct "all teams" method, use it. Your SimpleTeamService has allTeams().
            // plugin.teams() is the TeamService interface, but your instance is SimpleTeamService.
            if (plugin.teams() instanceof net.chumbucket.sorekillteams.service.SimpleTeamService sts) {
                return sts.allTeams().stream()
                        .filter(Objects::nonNull)
                        .map(Team::getName)
                        .filter(n -> n != null && !n.isBlank())
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }
        } catch (Throwable ignored) {}

        // Fallback: infer from online players' teams (not perfect, but safe)
        try {
            Set<String> names = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) continue;
                plugin.teams().getTeamByPlayer(p.getUniqueId())
                        .map(Team::getName)
                        .filter(n -> n != null && !n.isBlank())
                        .ifPresent(names::add);
            }
            return names.stream()
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private List<String> partial(String token, Collection<String> options) {
        if (options == null || options.isEmpty()) return List.of();

        // match last token only (supports multi-word team names)
        String t = lastToken(token).toLowerCase(Locale.ROOT);

        return options.stream()
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> t.isEmpty() || s.toLowerCase(Locale.ROOT).startsWith(t))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static String joinFrom(String[] args, int startIndex) {
        if (args == null || args.length <= startIndex) return "";
        return String.join(" ", Arrays.copyOfRange(args, startIndex, args.length)).trim();
    }

    private static String lastToken(String joined) {
        if (joined == null) return "";
        String s = joined.trim();
        if (s.isEmpty()) return "";
        int idx = s.lastIndexOf(' ');
        return (idx < 0) ? s : s.substring(idx + 1);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean subIs(String sub, String canonical) {
        if (sub == null) return false;
        if (canonical.equals("reload")) return sub.equals("reload");
        if (canonical.equals("version")) return sub.equals("version");
        return sub.equalsIgnoreCase(canonical);
    }
}
