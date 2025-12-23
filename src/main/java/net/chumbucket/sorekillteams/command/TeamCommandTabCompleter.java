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

        // /team <sub>
        if (args.length == 1) {
            return partial(args[0], subcommandsFor(p));
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // /team ff <...>
        if (args.length == 2 && (sub.equals("ff") || sub.equals("friendlyfire"))) {
            if (!p.hasPermission("sorekillteams.ff")) return List.of();
            return partial(args[1], List.of("on", "off", "toggle", "status"));
        }

        // /team invite <player>
        if (args.length == 2 && sub.equals("invite")) {
            if (!p.hasPermission("sorekillteams.invite")) return List.of();
            return partial(args[1], onlinePlayerNamesExcluding(p.getName()));
        }

        // /team kick <player|uuid>  (Option A = online or uuid; we can at least suggest online names)
        if (args.length == 2 && sub.equals("kick")) {
            if (!p.hasPermission("sorekillteams.kick")) return List.of();
            return partial(args[1], onlinePlayerNamesExcluding(p.getName()));
        }

        // /team transfer <player|uuid>
        if (args.length == 2 && sub.equals("transfer")) {
            if (!p.hasPermission("sorekillteams.transfer")) return List.of();
            return partial(args[1], onlinePlayerNamesExcluding(p.getName()));
        }

        // /team accept <teamNameFromInvites...>
        // /team deny <teamNameFromInvites...>
        if (sub.equals("accept") || sub.equals("deny")) {
            if (sub.equals("accept") && !p.hasPermission("sorekillteams.accept")) return List.of();
            if (sub.equals("deny") && !p.hasPermission("sorekillteams.deny")) return List.of();

            String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            return partial(joined, inviteTeamNames(p.getUniqueId()));
        }

        // /team spy <team... | list | off | clear>
        if (sub.equals("spy")) {
            if (!p.hasPermission("sorekillteams.spy")) return List.of();

            String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();

            // First argument: show keywords + team names
            if (args.length >= 2) {
                List<String> options = new ArrayList<>();
                options.add("list");
                options.add("off");
                options.add("clear");

                // Add team names for convenience (multi-word supported by our 'joined' matching)
                options.addAll(allTeamNames());

                return partial(joined, options);
            }
        }

        // /team rename <...> (no good suggestions)
        return List.of();
    }

    private List<String> subcommandsFor(Player p) {
        List<String> subs = new ArrayList<>();

        if (p.hasPermission("sorekillteams.create")) subs.add("create");
        if (p.hasPermission("sorekillteams.invite")) subs.add("invite");
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

        // 1.0.8
        if (p.hasPermission("sorekillteams.spy")) subs.add("spy");

        return subs.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> onlinePlayerNamesExcluding(String name) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n != null && !n.isBlank())
                .filter(n -> !n.equalsIgnoreCase(name))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private List<String> inviteTeamNames(UUID invitee) {
        Collection<TeamInvite> invs = plugin.teams().getInvites(invitee);
        if (invs == null || invs.isEmpty()) return List.of();

        return invs.stream()
                .map(inv -> plugin.teams().getTeamById(inv.getTeamId())
                        .map(Team::getName)
                        .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team"))
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> allTeamNames() {
        // Best-effort: if you ever change internal storage, this won't crash.
        try {
            // SimpleTeamService exposes allTeams(); but TeamService doesn't.
            // So we can only safely enumerate if the runtime service actually has that method.
            // If not available, just return empty list.
            var svc = plugin.teams();
            try {
                var m = svc.getClass().getMethod("allTeams");
                Object res = m.invoke(svc);
                if (res instanceof Collection<?> col) {
                    List<String> names = new ArrayList<>();
                    for (Object o : col) {
                        if (o instanceof Team t) {
                            if (t.getName() != null && !t.getName().isBlank()) names.add(t.getName());
                        }
                    }
                    return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
                }
            } catch (NoSuchMethodException ignored) {
                // no enumeration available
            }
        } catch (Exception ignored) {}

        return List.of();
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
