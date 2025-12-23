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
import net.chumbucket.sorekillteams.service.TeamServiceException;
import net.chumbucket.sorekillteams.util.CommandErrors;
import net.chumbucket.sorekillteams.util.Msg;
import net.chumbucket.sorekillteams.util.TeamNameValidator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

public final class TeamCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;
    private final TeamNameValidator nameValidator;

    private static final DateTimeFormatter TEAM_CREATED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    private static final String SPY_PERMISSION = "sorekillteams.spy";

    public TeamCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.nameValidator = new TeamNameValidator(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg().send(sender, "player_only");
            return true;
        }

        if (!p.hasPermission("sorekillteams.use")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        if (args.length == 0) {
            plugin.msg().send(p, "team_usage");
            return true;
        }

        final boolean debug = plugin.getConfig().getBoolean("commands.debug", false);
        final String sub = args[0].toLowerCase(Locale.ROOT);

        try {
            switch (sub) {

                // =========================
                // 1.0.8: /team spy ...
                // =========================
                case "spy" -> {
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

                    String arg1 = args[1].trim();

                    if (arg1.equalsIgnoreCase("list")) {
                        Collection<Team> spied = plugin.teams().getSpiedTeams(p.getUniqueId());
                        if (spied.isEmpty()) {
                            plugin.msg().send(p, "team_spy_list_empty");
                            return true;
                        }

                        String joined = spied.stream()
                                .filter(Objects::nonNull)
                                .map(t -> Msg.color(t.getName()))
                                .collect(Collectors.joining(", "));

                        plugin.msg().send(p, "team_spy_list",
                                "{teams}", Msg.color(joined)
                        );
                        return true;
                    }

                    if (arg1.equalsIgnoreCase("off") || arg1.equalsIgnoreCase("clear")) {
                        plugin.teams().clearSpy(p.getUniqueId());
                        plugin.msg().send(p, "team_spy_cleared");
                        return true;
                    }

                    String teamNameRaw = joinArgsAfter(args, 1);
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

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " spy toggled team=" + team.getName() + " -> " + (enabled ? "ON" : "OFF"));
                    return true;
                }

                // -------------------------
                // existing cases (unchanged)
                // -------------------------

                case "create" -> {
                    if (!p.hasPermission("sorekillteams.create")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_create_usage");
                        return true;
                    }

                    String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    TeamNameValidator.Validation v = nameValidator.validate(rawName);
                    if (!v.ok()) {
                        plugin.msg().send(p, v.reasonKey());
                        return true;
                    }

                    Team t = plugin.teams().createTeam(p.getUniqueId(), v.plainName());

                    plugin.msg().send(p, "team_created",
                            "{team}", Msg.color(t.getName())
                    );

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " created team=" + t.getName());
                    return true;
                }

                case "invite" -> {
                    if (!p.hasPermission("sorekillteams.invite")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_invite_usage");
                        return true;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        plugin.msg().send(p, "team_invite_player_offline");
                        return true;
                    }

                    plugin.teams().invite(p.getUniqueId(), target.getUniqueId());

                    String teamName = plugin.teams().getTeamByPlayer(p.getUniqueId())
                            .map(Team::getName)
                            .orElse("Team");

                    plugin.msg().send(p, "team_invite_sent",
                            "{target}", target.getName(),
                            "{team}", Msg.color(teamName)
                    );

                    plugin.msg().send(target, "team_invite_received",
                            "{inviter}", p.getName(),
                            "{team}", Msg.color(teamName)
                    );

                    plugin.msg().send(target, "team_usage");
                    plugin.msg().send(target, "team_invites_tip");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " invited " + target.getName() + " team=" + teamName);
                    return true;
                }

                case "invites" -> {
                    if (!p.hasPermission("sorekillteams.invites")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Collection<TeamInvite> invs = plugin.teams().getInvites(p.getUniqueId());
                    if (invs.isEmpty()) {
                        plugin.msg().send(p, "team_invites_none");
                        return true;
                    }

                    List<TeamInvite> sorted = invs.stream()
                            .sorted(Comparator.comparingLong(TeamInvite::getExpiresAtMs))
                            .toList();

                    plugin.msg().send(p, "team_invites_header");

                    long now = System.currentTimeMillis();
                    for (TeamInvite inv : sorted) {
                        String teamName = plugin.teams().getTeamById(inv.getTeamId())
                                .map(Team::getName)
                                .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

                        String inviterName = nameOf(inv.getInviter());
                        long secondsLeft = inv.getSecondsRemaining(now);

                        plugin.msg().send(p, "team_invites_entry",
                                "{team}", Msg.color(teamName),
                                "{inviter}", inviterName,
                                "{seconds}", String.valueOf(secondsLeft)
                        );
                    }

                    if (sorted.size() > 1) {
                        plugin.msg().send(p, "team_invites_tip");
                    }

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " viewed invites count=" + sorted.size());
                    return true;
                }

                case "accept" -> {
                    if (!p.hasPermission("sorekillteams.accept")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Optional<UUID> teamId = Optional.empty();
                    if (args.length >= 2) {
                        String teamArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        teamId = resolveInviteTeamIdByName(p.getUniqueId(), teamArg);
                        if (teamId.isEmpty()) {
                            plugin.msg().send(p, "team_invite_no_match");
                            return true;
                        }
                    }

                    Optional<TeamInvite> invOpt = plugin.teams().acceptInvite(p.getUniqueId(), teamId);
                    if (invOpt.isEmpty()) {
                        plugin.msg().send(p, "team_no_invites");
                        return true;
                    }

                    TeamInvite inv = invOpt.get();

                    String teamName = plugin.teams().getTeamById(inv.getTeamId())
                            .map(Team::getName)
                            .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

                    String inviterName = nameOf(inv.getInviter());

                    plugin.msg().send(p, "team_joined",
                            "{team}", Msg.color(teamName)
                    );
                    plugin.msg().send(p, "team_joined_who",
                            "{inviter}", inviterName
                    );

                    Player inviterOnline = Bukkit.getPlayer(inv.getInviter());
                    if (inviterOnline != null) {
                        plugin.msg().send(inviterOnline, "team_invite_accepted_inviter",
                                "{player}", p.getName(),
                                "{team}", Msg.color(teamName)
                        );
                    }

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " accepted invite team=" + teamName);
                    return true;
                }

                case "deny" -> {
                    if (!p.hasPermission("sorekillteams.deny")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Optional<UUID> teamId = Optional.empty();
                    if (args.length >= 2) {
                        String teamArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        teamId = resolveInviteTeamIdByName(p.getUniqueId(), teamArg);
                        if (teamId.isEmpty()) {
                            plugin.msg().send(p, "team_invite_no_match");
                            return true;
                        }
                    }

                    boolean ok = plugin.teams().denyInvite(p.getUniqueId(), teamId);
                    if (!ok) {
                        plugin.msg().send(p, "team_no_invites");
                        return true;
                    }

                    plugin.msg().send(p, "team_invite_denied");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " denied invite");
                    return true;
                }

                case "leave" -> {
                    if (!p.hasPermission("sorekillteams.leave")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    plugin.teams().leaveTeam(p.getUniqueId());
                    plugin.msg().send(p, "team_left");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " left team");
                    return true;
                }

                case "disband" -> {
                    if (!p.hasPermission("sorekillteams.disband")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    plugin.teams().disbandTeam(p.getUniqueId());

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " disbanded team");
                    return true;
                }

                case "rename" -> {
                    if (!p.hasPermission("sorekillteams.rename")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_rename_usage");
                        return true;
                    }

                    String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    TeamNameValidator.Validation v = nameValidator.validate(rawName);
                    if (!v.ok()) {
                        plugin.msg().send(p, v.reasonKey());
                        return true;
                    }

                    Team before = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    String oldName = before != null ? before.getName() : "Team";

                    plugin.teams().renameTeam(p.getUniqueId(), v.plainName());

                    Team after = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    String newName = after != null ? after.getName() : v.plainName();

                    plugin.msg().send(p, "team_renamed",
                            "{old}", Msg.color(oldName),
                            "{team}", Msg.color(newName)
                    );

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " renamed team old=" + oldName + " new=" + newName);
                    return true;
                }

                case "kick" -> {
                    if (!p.hasPermission("sorekillteams.kick")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_kick_usage");
                        return true;
                    }

                    UUID targetUuid = resolvePlayerUuidOnlineOrUuid(args[1]);
                    if (targetUuid == null) {
                        plugin.msg().send(p, "team_player_must_be_online_or_uuid");
                        return true;
                    }

                    plugin.teams().kickMember(p.getUniqueId(), targetUuid);

                    String targetName = nameOf(targetUuid);
                    Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    String teamName = (t != null ? t.getName() : "Team");

                    plugin.msg().send(p, "team_kick_success",
                            "{player}", targetName,
                            "{team}", Msg.color(teamName)
                    );

                    Player targetOnline = Bukkit.getPlayer(targetUuid);
                    if (targetOnline != null) {
                        plugin.msg().send(targetOnline, "team_kick_target",
                                "{team}", Msg.color(teamName),
                                "{by}", p.getName()
                        );
                    }

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " kicked uuid=" + targetUuid);
                    return true;
                }

                case "transfer" -> {
                    if (!p.hasPermission("sorekillteams.transfer")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_transfer_usage");
                        return true;
                    }

                    UUID targetUuid = resolvePlayerUuidOnlineOrUuid(args[1]);
                    if (targetUuid == null) {
                        plugin.msg().send(p, "team_player_must_be_online_or_uuid");
                        return true;
                    }

                    plugin.teams().transferOwnership(p.getUniqueId(), targetUuid);

                    String targetName = nameOf(targetUuid);
                    Team t = plugin.teams().getTeamByPlayer(targetUuid).orElse(null);
                    String teamName = (t != null ? t.getName() : "Team");

                    plugin.msg().send(p, "team_transfer_success",
                            "{player}", targetName,
                            "{team}", Msg.color(teamName)
                    );

                    Player targetOnline = Bukkit.getPlayer(targetUuid);
                    if (targetOnline != null) {
                        plugin.msg().send(targetOnline, "team_transfer_received",
                                "{team}", Msg.color(teamName),
                                "{by}", p.getName()
                        );
                    }

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " transferred ownership -> " + targetUuid);
                    return true;
                }

                case "info" -> {
                    if (!p.hasPermission("sorekillteams.info")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (t == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }

                    String ownerName = nameOf(t.getOwner());

                    String members = t.getMembers().stream()
                            .map(uuid -> {
                                Player online = Bukkit.getPlayer(uuid);
                                if (online != null) {
                                    return Msg.color("&a" + online.getName());
                                }
                                OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
                                String n = (off != null && off.getName() != null && !off.getName().isBlank())
                                        ? off.getName()
                                        : uuid.toString().substring(0, 8);
                                return Msg.color("&c" + n);
                            })
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.joining(Msg.color("&7, ")));

                    String created = TEAM_CREATED_FMT.format(Instant.ofEpochMilli(t.getCreatedAtMs()));

                    boolean tc = plugin.teams().isTeamChatEnabled(p.getUniqueId());
                    String tcState = tc ? "&aON" : "&cOFF";

                    String ffState = t.isFriendlyFireEnabled() ? "&aON" : "&cOFF";

                    plugin.msg().send(p, "team_info_header");
                    plugin.msg().send(p, "team_info_name", "{team}", Msg.color(t.getName()));
                    plugin.msg().send(p, "team_info_owner", "{owner}", ownerName);
                    plugin.msg().send(p, "team_info_members",
                            "{count}", String.valueOf(t.getMembers().size()),
                            "{members}", members
                    );

                    plugin.msg().send(p, "team_info_legend");

                    plugin.msg().send(p, "team_info_created", "{date}", created);
                    plugin.msg().send(p, "team_info_tc", "{state}", Msg.color(tcState));
                    plugin.msg().send(p, "team_info_ff", "{state}", Msg.color(ffState));

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " viewed team info team=" + t.getName());
                    return true;
                }

                case "ff", "friendlyfire" -> {
                    if (!p.hasPermission("sorekillteams.ff")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (t == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }

                    if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
                        String state = t.isFriendlyFireEnabled() ? "&aON" : "&cOFF";
                        plugin.msg().send(p, "team_ff_status", "{state}", Msg.color(state));
                        plugin.msg().send(p, "team_ff_usage");
                        return true;
                    }

                    if (!t.getOwner().equals(p.getUniqueId())) {
                        plugin.msg().send(p, "team_not_owner");
                        return true;
                    }

                    String mode = args[1].toLowerCase(Locale.ROOT);
                    boolean newValue;

                    switch (mode) {
                        case "on", "true", "enable", "enabled" -> newValue = true;
                        case "off", "false", "disable", "disabled" -> newValue = false;
                        case "toggle" -> newValue = !t.isFriendlyFireEnabled();
                        default -> {
                            plugin.msg().send(p, "team_ff_usage");
                            return true;
                        }
                    }

                    t.setFriendlyFireEnabled(newValue);

                    try {
                        if (plugin.storage() != null && plugin.teams() != null) {
                            plugin.storage().saveAll(plugin.teams());
                        }
                    } catch (Exception ignored) {}

                    plugin.msg().send(p, newValue ? "team_ff_status_on" : "team_ff_status_off");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " ff -> " + (newValue ? "ON" : "OFF"));
                    return true;
                }

                default -> {
                    plugin.msg().send(p, "unknown_command");
                    plugin.msg().send(p, "team_usage");
                    return true;
                }
            }
        } catch (TeamServiceException ex) {
            CommandErrors.send(p, plugin, ex);

            if (debug) {
                plugin.getLogger().info("[TEAM-DBG] TeamServiceException sub=" + sub + " player=" + p.getName() + " code=" +
                        (ex.code() == null ? "null" : ex.code().name()));
            }
            return true;

        } catch (Exception ex) {
            p.sendMessage(plugin.msg().prefix() + "An error occurred.");
            plugin.getLogger().severe("Command error (" + sub + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return true;
        }
    }

    private Optional<UUID> resolveInviteTeamIdByName(UUID invitee, String teamArgRaw) {
        String wanted = normalize(teamArgRaw);

        for (TeamInvite inv : plugin.teams().getInvites(invitee)) {
            String teamName = plugin.teams().getTeamById(inv.getTeamId())
                    .map(Team::getName)
                    .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

            if (normalize(teamName).equalsIgnoreCase(wanted)) {
                return Optional.of(inv.getTeamId());
            }
        }
        return Optional.empty();
    }

    private String normalize(String s) {
        if (s == null) return "";
        String colored = Msg.color(s);
        String stripped = ChatColor.stripColor(colored);
        return stripped == null ? "" : stripped.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();

        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();

        return uuid.toString().substring(0, 8);
    }

    private UUID resolvePlayerUuidOnlineOrUuid(String arg) {
        if (arg == null || arg.isBlank()) return null;

        try {
            return UUID.fromString(arg);
        } catch (IllegalArgumentException ignored) {}

        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) return online.getUniqueId();

        return null;
    }

    // join args AFTER index (e.g. after "spy")
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