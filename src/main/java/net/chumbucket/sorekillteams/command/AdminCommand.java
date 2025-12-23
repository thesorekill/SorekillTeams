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
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public final class AdminCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;

    private static final DateTimeFormatter TEAM_CREATED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    public AdminCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!sender.hasPermission("sorekillteams.admin")) {
            plugin.msg().send(sender, "no_permission");
            return true;
        }

        if (args.length == 0) {
            plugin.msg().send(sender, "admin_usage");
            sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Admin: /" + label + " disband|setowner|kick|info"));
            return true;
        }

        final boolean debug = plugin.getConfig().getBoolean("commands.debug", false);
        final String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload", "rl", "r" -> {
                if (!requirePerm(sender, "sorekillteams.admin.reload")) return true;
                if (debug) plugin.getLogger().info("[ADMIN-DBG] reload by " + sender.getName());
                plugin.reloadEverything();
                plugin.msg().send(sender, "reloaded");
                return true;
            }

            case "version", "ver", "v" -> {
                if (debug) plugin.getLogger().info("[ADMIN-DBG] version by " + sender.getName());
                plugin.msg().send(sender, "version", "{version}", plugin.getDescription().getVersion());
                return true;
            }

            case "disband" -> {
                if (!requirePerm(sender, "sorekillteams.admin.disband")) return true;
                if (args.length < 2) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Usage: /" + label + " disband <team>"));
                    return true;
                }

                String teamName = joinArgsAfter(args, 0);
                Team t = plugin.teams().getTeamByName(teamName).orElse(null);
                if (t == null) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&cTeam not found: &f" + teamName));
                    return true;
                }

                plugin.teams().adminDisbandTeam(t.getId());
                sender.sendMessage(Msg.color(plugin.msg().prefix() + "&aDisbanded team &c" + t.getName() + "&a."));

                if (debug) plugin.getLogger().info("[ADMIN-DBG] disband team=" + t.getName() + " by " + sender.getName());
                return true;
            }

            case "setowner" -> {
                if (!requirePerm(sender, "sorekillteams.admin.setowner")) return true;
                if (args.length < 3) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Usage: /" + label + " setowner <team> <playerOnline|uuid>"));
                    return true;
                }

                String targetToken = args[args.length - 1];
                String teamName = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1)).trim();

                Team t = plugin.teams().getTeamByName(teamName).orElse(null);
                if (t == null) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&cTeam not found: &f" + teamName));
                    return true;
                }

                UUID newOwner = resolvePlayerUuidOnlineOrUuid(targetToken);
                if (newOwner == null) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&cPlayer must be online or a UUID: &f" + targetToken));
                    return true;
                }

                plugin.teams().adminSetOwner(t.getId(), newOwner);
                sender.sendMessage(Msg.color(plugin.msg().prefix() + "&aSet owner of &c" + t.getName() + "&a to &f" + nameOf(newOwner) + "&a."));

                if (debug) plugin.getLogger().info("[ADMIN-DBG] setowner team=" + t.getName() + " newOwner=" + newOwner + " by " + sender.getName());
                return true;
            }

            case "kick" -> {
                if (!requirePerm(sender, "sorekillteams.admin.kick")) return true;
                if (args.length < 2) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Usage: /" + label + " kick <playerOnline|uuid>"));
                    return true;
                }

                UUID target = resolvePlayerUuidOnlineOrUuid(args[1]);
                if (target == null) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&cPlayer must be online or a UUID: &f" + args[1]));
                    return true;
                }

                Team before = plugin.teams().getTeamByPlayer(target).orElse(null);
                if (before == null) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&eThat player is not in a team."));
                    return true;
                }

                plugin.teams().adminKickPlayer(target);

                sender.sendMessage(Msg.color(plugin.msg().prefix()
                        + "&aKicked &f" + nameOf(target) + "&a from &c" + before.getName() + "&a."));

                if (debug) plugin.getLogger().info("[ADMIN-DBG] kick player=" + target + " from team=" + before.getName() + " by " + sender.getName());
                return true;
            }

            case "info" -> {
                if (!requirePerm(sender, "sorekillteams.admin.info")) return true;
                if (args.length < 2) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Usage: /" + label + " info <team>"));
                    return true;
                }

                String teamName = joinArgsAfter(args, 0);
                Team t = plugin.teams().getTeamByName(teamName).orElse(null);
                if (t == null) {
                    sender.sendMessage(Msg.color(plugin.msg().prefix() + "&cTeam not found: &f" + teamName));
                    return true;
                }

                sendTeamInfo(sender, t);

                if (debug) plugin.getLogger().info("[ADMIN-DBG] info team=" + t.getName() + " by " + sender.getName());
                return true;
            }

            default -> {
                plugin.msg().send(sender, "unknown_command");
                plugin.msg().send(sender, "admin_usage");
                sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Admin: /" + label + " disband|setowner|kick|info"));
                return true;
            }
        }
    }

    private boolean requirePerm(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return true;
        plugin.msg().send(sender, "no_permission");
        return false;
    }

    private void sendTeamInfo(CommandSender sender, Team t) {
        String ownerName = nameOf(t.getOwner());

        String members = t.getMembers().stream()
                .filter(Objects::nonNull)
                .map(uuid -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) return Msg.color("&a" + online.getName());

                    // No offline name lookup here; just show short UUID
                    return Msg.color("&c" + uuid.toString().substring(0, 8));
                })
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(Msg.color("&7, ")));

        String created = TEAM_CREATED_FMT.format(Instant.ofEpochMilli(t.getCreatedAtMs()));
        String ffState = t.isFriendlyFireEnabled() ? "&aON" : "&cOFF";

        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&8&m-----&r &cTeam Info &8&m-----"));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fTeam: &c" + t.getName()));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fOwner: &c" + ownerName));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fMembers (&c" + t.getMembers().size() + "&f): " + members));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fCreated: &c" + created));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fTeam friendly fire: " + Msg.color(ffState)));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Legend: &aOnline &7/ &cOffline"));
    }

    /**
     * Option 2: only allow online player names or UUID literals.
     * No deprecated offline-name APIs used.
     */
    private UUID resolvePlayerUuidOnlineOrUuid(String arg) {
        if (arg == null || arg.isBlank()) return null;

        try {
            return UUID.fromString(arg);
        } catch (IllegalArgumentException ignored) {}

        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) return online.getUniqueId();

        return null;
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();

        // No offline name lookup; show short UUID
        return uuid.toString().substring(0, 8);
    }

    private String joinArgsAfter(String[] args, int indexOfSubcommand) {
        int start = Math.max(0, indexOfSubcommand + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (args[i] == null) continue;
            String s = args[i].trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString().trim();
    }
}
