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
import net.chumbucket.sorekillteams.model.TeamInvite;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class TeamCommandTabCompleter implements TabCompleter {

    private final SorekillTeamsPlugin plugin;

    public TeamCommandTabCompleter(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!(sender instanceof Player p)) return List.of();
        if (!p.hasPermission("sorekillteams.use")) return List.of();

        if (args.length == 1) {
            return partial(args[0], subcommandsFor(p));
        }

        String sub = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("chat") || sub.equals("tc") || sub.equals("teamchat")) {
            if (!isTeamChatEnabledInConfig()) return List.of();
            if (!p.hasPermission("sorekillteams.teamchat")) return List.of();

            if (args.length == 2) {
                return partial(args[1], List.of("on", "off", "toggle", "status"));
            }
            return List.of();
        }

        if (args.length == 2 && (sub.equals("ff") || sub.equals("friendlyfire"))) {
            if (!p.hasPermission("sorekillteams.ff")) return List.of();
            return partial(args[1], List.of("on", "off", "toggle", "status"));
        }

        if (args.length == 2 && sub.equals("invite")) {
            if (!p.hasPermission("sorekillteams.invite")) return List.of();
            return partial(args[1], onlinePlayerNamesExcluding(p.getName()));
        }

        if (args.length == 2 && sub.equals("kick")) {
            if (!p.hasPermission("sorekillteams.kick")) return List.of();
            return partial(args[1], onlinePlayerNamesExcluding(p.getName()));
        }

        if (args.length == 2 && sub.equals("transfer")) {
            if (!p.hasPermission("sorekillteams.transfer")) return List.of();
            return partial(args[1], onlinePlayerNamesExcluding(p.getName()));
        }

        if (sub.equals("accept") || sub.equals("deny")) {
            if (sub.equals("accept") && !p.hasPermission("sorekillteams.accept")) return List.of();
            if (sub.equals("deny") && !p.hasPermission("sorekillteams.deny")) return List.of();

            String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            return partial(joined, inviteTeamNames(p.getUniqueId()));
        }

        if (sub.equals("spy")) {
            if (!p.hasPermission("sorekillteams.spy")) return List.of();

            String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();

            List<String> options = new ArrayList<>(List.of("list", "off", "clear"));
            options.addAll(allTeamNames(p));

            return partial(joined, options);
        }

        return List.of();
    }

    private List<String> subcommandsFor(Player p) {
        List<String> subs = new ArrayList<>();

        if (p.hasPermission("sorekillteams.create")) subs.add("create");
        if (p.hasPermission("sorekillteams.invite")) subs.add("invite");

        // ✅ 1.1.3: invites is its own permission now
        if (p.hasPermission("sorekillteams.invites")) subs.add("invites");

        if (p.hasPermission("sorekillteams.accept")) subs.add("accept");
        if (p.hasPermission("sorekillteams.deny")) subs.add("deny");
        if (p.hasPermission("sorekillteams.leave")) subs.add("leave");
        if (p.hasPermission("sorekillteams.disband")) subs.add("disband");
        if (p.hasPermission("sorekillteams.info")) subs.add("info");
        if (p.hasPermission("sorekillteams.ff")) subs.add("ff");
        if (p.hasPermission("sorekillteams.kick")) subs.add("kick");
        if (p.hasPermission("sorekillteams.transfer")) subs.add("transfer");
        if (p.hasPermission("sorekillteams.rename")) subs.add("rename");
        if (p.hasPermission("sorekillteams.spy")) subs.add("spy");

        if (isTeamChatEnabledInConfig() && p.hasPermission("sorekillteams.teamchat")) {
            subs.add("chat");
        }

        return subs.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private boolean isTeamChatEnabledInConfig() {
        try {
            if (!plugin.getConfig().getBoolean("chat.enabled", true)) return false;
            if (!plugin.getConfig().getBoolean("chat.toggle_enabled", true)) return false;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> onlinePlayerNamesExcluding(String name) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n != null && !n.isBlank())
                .filter(n -> name == null || !n.equalsIgnoreCase(name))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private List<String> inviteTeamNames(UUID invitee) {
        Collection<TeamInvite> invs;
        try {
            invs = plugin.teams().getInvites(invitee);
        } catch (Exception ignored) {
            return List.of();
        }

        if (invs == null || invs.isEmpty()) return List.of();

        return invs.stream()
                .filter(Objects::nonNull)
                .map(inv -> plugin.teams().getTeamById(inv.getTeamId())
                        .map(Team::getName)
                        .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team"))
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> allTeamNames(Player completer) {
        try {
            Set<String> names = new HashSet<>();

            for (Player online : Bukkit.getOnlinePlayers()) {
                plugin.teams().getTeamByPlayer(online.getUniqueId())
                        .map(Team::getName)
                        .filter(n -> n != null && !n.isBlank())
                        .ifPresent(names::add);
            }

            names.addAll(inviteTeamNames(completer.getUniqueId()));

            try {
                Collection<Team> spied = plugin.teams().getSpiedTeams(completer.getUniqueId());
                if (spied != null) {
                    for (Team t : spied) {
                        if (t == null) continue;
                        String n = t.getName();
                        if (n != null && !n.isBlank()) names.add(n);
                    }
                }
            } catch (Exception ignored) {}

            return names.stream()
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> partial(String token, Collection<String> options) {
        if (options == null || options.isEmpty()) return List.of();
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);

        return options.stream()
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> t.isEmpty() || s.toLowerCase(Locale.ROOT).startsWith(t))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
