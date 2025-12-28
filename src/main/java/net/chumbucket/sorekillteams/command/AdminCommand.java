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
import org.bukkit.OfflinePlayer;
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

    private static final String PERM_ADMIN = "sorekillteams.admin";
    private static final String PERM_RELOAD = "sorekillteams.admin.reload";
    private static final String PERM_VERSION = "sorekillteams.admin.version";
    private static final String PERM_DISBAND = "sorekillteams.admin.disband";
    private static final String PERM_SETOWNER = "sorekillteams.admin.setowner";
    private static final String PERM_KICK = "sorekillteams.admin.kick";
    private static final String PERM_INFO = "sorekillteams.admin.info";

    private static final DateTimeFormatter TEAM_CREATED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    public AdminCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // Umbrella admin gate
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.msg().send(sender, "no_permission");
            return true;
        }

        if (args.length == 0) {
            plugin.msg().send(sender, "admin_usage");
            sender.sendMessage(Msg.color(plugin.msg().prefix()
                    + "&7Admin: /" + label + " reload|version|disband|setowner|kick|info"));
            return true;
        }

        final boolean debug = plugin.getConfig().getBoolean("admin.debug",
                plugin.getConfig().getBoolean("chat.debug", false));

        final String sub = (args[0] == null ? "" : args[0]).toLowerCase(Locale.ROOT);

        try {
            switch (sub) {
                case "reload", "rl", "r" -> {
                    if (!requirePerm(sender, PERM_RELOAD)) return true;

                    if (debug) plugin.getLogger().info("[ADMIN-DBG] reload by " + sender.getName());
                    plugin.reloadEverything();
                    plugin.msg().send(sender, "reloaded");
                    return true;
                }

                case "version", "ver", "v" -> {
                    if (!requirePerm(sender, PERM_VERSION)) return true;

                    if (debug) plugin.getLogger().info("[ADMIN-DBG] version by " + sender.getName());
                    plugin.msg().send(sender, "version", "{version}", plugin.getDescription().getVersion());
                    return true;
                }

                case "disband" -> {
                    if (!requirePerm(sender, PERM_DISBAND)) return true;

                    if (!plugin.getConfig().getBoolean("admin.allow_force_disband", true)) {
                        plugin.msg().send(sender, "admin_action_disabled");
                        return true;
                    }

                    if (args.length < 2) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&7Usage: /" + label + " disband <team>"));
                        return true;
                    }

                    // FIX: join args after subcommand (index 1), not index 0
                    String teamName = joinFrom(args, 1);
                    Team t = plugin.teams().getTeamByName(teamName).orElse(null);
                    if (t == null) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&cTeam not found: &f" + teamName));
                        return true;
                    }

                    plugin.teams().adminDisbandTeam(t.getId());
                    sender.sendMessage(Msg.color(plugin.msg().prefix()
                            + "&aDisbanded team &c" + t.getName() + "&a."));

                    if (debug) plugin.getLogger().info("[ADMIN-DBG] disband team=" + t.getName() + " by " + sender.getName());
                    return true;
                }

                case "setowner" -> {
                    if (!requirePerm(sender, PERM_SETOWNER)) return true;

                    if (!plugin.getConfig().getBoolean("admin.allow_set_owner", true)) {
                        plugin.msg().send(sender, "admin_action_disabled");
                        return true;
                    }

                    if (args.length < 3) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&7Usage: /" + label + " setowner <team> <playerOnline|uuid>"));
                        return true;
                    }

                    String targetToken = args[args.length - 1];
                    String teamName = joinFrom(args, 1, args.length - 1);

                    Team t = plugin.teams().getTeamByName(teamName).orElse(null);
                    if (t == null) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&cTeam not found: &f" + teamName));
                        return true;
                    }

                    UUID newOwner = resolvePlayerUuidOnlineOrUuid(targetToken);
                    if (newOwner == null) {
                        // If you want, swap this to your messages.yml key:
                        // team_player_must_be_online_or_uuid
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&cPlayer must be online or a UUID: &f" + targetToken));
                        return true;
                    }

                    plugin.teams().adminSetOwner(t.getId(), newOwner);
                    sender.sendMessage(Msg.color(plugin.msg().prefix()
                            + "&aSet owner of &c" + t.getName() + "&a to &f" + nameOf(newOwner) + "&a."));

                    if (debug) plugin.getLogger().info("[ADMIN-DBG] setowner team=" + t.getName() + " newOwner=" + newOwner + " by " + sender.getName());
                    return true;
                }

                case "kick" -> {
                    if (!requirePerm(sender, PERM_KICK)) return true;

                    if (!plugin.getConfig().getBoolean("admin.allow_force_kick", true)) {
                        plugin.msg().send(sender, "admin_action_disabled");
                        return true;
                    }

                    if (args.length < 2) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&7Usage: /" + label + " kick <playerOnline|uuid>"));
                        return true;
                    }

                    UUID target = resolvePlayerUuidOnlineOrUuid(args[1]);
                    if (target == null) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&cPlayer must be online or a UUID: &f" + args[1]));
                        return true;
                    }

                    Team before = plugin.teams().getTeamByPlayer(target).orElse(null);
                    if (before == null) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&eThat player is not in a team."));
                        return true;
                    }

                    plugin.teams().adminKickPlayer(target);

                    sender.sendMessage(Msg.color(plugin.msg().prefix()
                            + "&aKicked &f" + nameOf(target) + "&a from &c" + before.getName() + "&a."));

                    if (debug) plugin.getLogger().info("[ADMIN-DBG] kick player=" + target + " from team=" + before.getName() + " by " + sender.getName());
                    return true;
                }

                case "info" -> {
                    if (!requirePerm(sender, PERM_INFO)) return true;

                    if (args.length < 2) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&7Usage: /" + label + " info <team>"));
                        return true;
                    }

                    // FIX: join args after subcommand (index 1), not index 0
                    String teamName = joinFrom(args, 1);
                    Team t = plugin.teams().getTeamByName(teamName).orElse(null);
                    if (t == null) {
                        sender.sendMessage(Msg.color(plugin.msg().prefix()
                                + "&cTeam not found: &f" + teamName));
                        return true;
                    }

                    sendTeamInfo(sender, t);

                    if (debug) plugin.getLogger().info("[ADMIN-DBG] info team=" + t.getName() + " by " + sender.getName());
                    return true;
                }

                default -> {
                    plugin.msg().send(sender, "unknown_command");
                    plugin.msg().send(sender, "admin_usage");
                    sender.sendMessage(Msg.color(plugin.msg().prefix()
                            + "&7Admin: /" + label + " reload|version|disband|setowner|kick|info"));
                    return true;
                }
            }
        } catch (Exception ex) {
            sender.sendMessage(Msg.color(plugin.msg().prefix() + "&cAn error occurred."));
            plugin.getLogger().severe("Admin command error (" + sub + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return true;
        }
    }

    private boolean requirePerm(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return true;
        plugin.msg().send(sender, "no_permission");
        return false;
    }

    private void sendTeamInfo(CommandSender sender, Team t) {
        String ownerName = nameOf(t.getOwner());

        // Unique, non-null members
        LinkedHashSet<UUID> uniqueMembers = new LinkedHashSet<>();
        for (UUID u : t.getMembers()) {
            if (u != null) uniqueMembers.add(u);
        }
        if (t.getOwner() != null) uniqueMembers.add(t.getOwner());

        String members = uniqueMembers.stream()
                .map(uuid -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) return Msg.color("&a" + online.getName());

                    String offName = offlineNameOf(uuid);
                    if (offName != null && !offName.isBlank()) return Msg.color("&c" + offName);

                    return Msg.color("&c" + uuid.toString().substring(0, 8));
                })
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(Msg.color("&7, ")));

        String created = TEAM_CREATED_FMT.format(Instant.ofEpochMilli(t.getCreatedAtMs()));
        String ffState = t.isFriendlyFireEnabled() ? "&aON" : "&cOFF";

        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&8&m-----&r &cTeam Info &8&m-----"));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fTeam: &c" + t.getName()));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fOwner: &c" + ownerName));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fMembers (&c" + uniqueMembers.size() + "&f): " + members));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fCreated: &c" + created));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&fTeam friendly fire: " + Msg.color(ffState)));
        sender.sendMessage(Msg.color(plugin.msg().prefix() + "&7Legend: &aOnline &7/ &cOffline"));
    }

    private UUID resolvePlayerUuidOnlineOrUuid(String arg) {
        if (arg == null || arg.isBlank()) return null;

        // UUID first
        try {
            return UUID.fromString(arg.trim());
        } catch (IllegalArgumentException ignored) {}

        // then online exact name
        Player online = Bukkit.getPlayerExact(arg.trim());
        if (online != null) return online.getUniqueId();

        return null;
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();

        String off = offlineNameOf(uuid);
        if (off != null && !off.isBlank()) return off;

        return uuid.toString().substring(0, 8);
    }

    private String offlineNameOf(UUID uuid) {
        try {
            OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
            return off == null ? null : off.getName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String joinFrom(String[] args, int startInclusive) {
        return joinFrom(args, startInclusive, args == null ? 0 : args.length);
    }

    private static String joinFrom(String[] args, int startInclusive, int endExclusive) {
        if (args == null || args.length == 0) return "";
        int start = Math.max(0, startInclusive);
        int end = Math.min(args.length, Math.max(start, endExclusive));

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (args[i] == null) continue;
            String s = args[i].trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString().trim();
    }
}