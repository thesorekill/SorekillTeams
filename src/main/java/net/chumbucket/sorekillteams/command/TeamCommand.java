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
import net.chumbucket.sorekillteams.util.Msg;
import net.chumbucket.sorekillteams.util.TeamNameValidator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class TeamCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;
    private final TeamNameValidator nameValidator;

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

        if (args.length == 0) {
            p.sendMessage(plugin.msg().prefix() + "Use: /team create|invite|invites|accept|deny|leave|info");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        try {
            switch (sub) {
                case "create" -> {
                    if (!p.hasPermission("sorekillteams.create")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(plugin.msg().prefix() + "Usage: /team create <name>");
                        return true;
                    }

                    String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

                    TeamNameValidator.Validation v = nameValidator.validate(rawName);
                    if (!v.ok()) {
                        plugin.msg().send(p, v.reasonKey());
                        return true;
                    }

                    Team t = plugin.teams().createTeam(p.getUniqueId(), v.coloredName());

                    plugin.msg().send(p, "team_created",
                            "{team}", Msg.color(t.getName())
                    );
                    return true;
                }

                case "invite" -> {
                    if (!p.hasPermission("sorekillteams.invite")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(plugin.msg().prefix() + "Usage: /team invite <player>");
                        return true;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        p.sendMessage(plugin.msg().prefix() + "That player is not online.");
                        return true;
                    }
                    if (target.getUniqueId().equals(p.getUniqueId())) {
                        p.sendMessage(plugin.msg().prefix() + "You can't invite yourself.");
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

                    target.sendMessage(plugin.msg().prefix() + "Use /team invites to view invites. Use /team accept <team> to accept.");
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
                            .sorted(Comparator.comparingLong(TeamInvite::expiresAtMs))
                            .toList();

                    plugin.msg().send(p, "team_invites_header");

                    long now = System.currentTimeMillis();
                    for (TeamInvite inv : sorted) {
                        String teamName = plugin.teams().getTeamById(inv.teamId())
                                .map(Team::getName)
                                .orElse("Team");

                        String inviterName = nameOf(inv.inviter());
                        long secondsLeft = Math.max(0L, (inv.expiresAtMs() - now + 999) / 1000);

                        plugin.msg().send(p, "team_invites_entry",
                                "{team}", Msg.color(teamName),
                                "{inviter}", inviterName,
                                "{seconds}", String.valueOf(secondsLeft)
                        );
                    }

                    if (sorted.size() > 1) {
                        p.sendMessage(plugin.msg().prefix() + "Use /team accept <team> or /team deny <team>.");
                    }

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
                            p.sendMessage(plugin.msg().prefix() + "No invite matched that team. Use /team invites.");
                            return true;
                        }
                    }

                    Optional<TeamInvite> invOpt = plugin.teams().acceptInvite(p.getUniqueId(), teamId);
                    if (invOpt.isEmpty()) {
                        plugin.msg().send(p, "team_no_invites");
                        return true;
                    }

                    TeamInvite inv = invOpt.get();

                    String teamName = plugin.teams().getTeamById(inv.teamId())
                            .map(Team::getName)
                            .orElse("Team");

                    String inviterName = nameOf(inv.inviter());

                    plugin.msg().send(p, "team_joined",
                            "{team}", Msg.color(teamName)
                    );
                    plugin.msg().send(p, "team_joined_who",
                            "{inviter}", inviterName
                    );

                    Player inviterOnline = Bukkit.getPlayer(inv.inviter());
                    if (inviterOnline != null) {
                        plugin.msg().send(inviterOnline, "team_invite_accepted_inviter",
                                "{player}", p.getName(),
                                "{team}", Msg.color(teamName)
                        );
                    }

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
                            p.sendMessage(plugin.msg().prefix() + "No invite matched that team. Use /team invites.");
                            return true;
                        }
                    }

                    boolean ok = plugin.teams().denyInvite(p.getUniqueId(), teamId);
                    if (!ok) {
                        plugin.msg().send(p, "team_no_invites");
                        return true;
                    }

                    plugin.msg().send(p, "team_invite_denied");
                    return true;
                }

                case "leave" -> {
                    if (!p.hasPermission("sorekillteams.leave")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    plugin.teams().leaveTeam(p.getUniqueId());
                    p.sendMessage(plugin.msg().prefix() + "You left your team.");
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
                            .map(this::nameOf)
                            .collect(Collectors.joining(", "));

                    plugin.msg().send(p, "team_info_header");
                    plugin.msg().send(p, "team_info_name", "{team}", Msg.color(t.getName()));
                    plugin.msg().send(p, "team_info_owner", "{owner}", ownerName);
                    plugin.msg().send(p, "team_info_members",
                            "{count}", String.valueOf(t.getMembers().size()),
                            "{members}", members
                    );
                    return true;
                }

                default -> {
                    p.sendMessage(plugin.msg().prefix() + "Unknown subcommand. Use: /team create|invite|invites|accept|deny|leave|info");
                    return true;
                }
            }
        } catch (IllegalStateException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();

            if (msg.equalsIgnoreCase("TEAM_FULL")) {
                plugin.msg().send(p, "team_team_full");
                return true;
            }
            if (msg.equalsIgnoreCase("INVITE_EXPIRED")) {
                plugin.msg().send(p, "team_invite_expired");
                return true;
            }
            if (msg.equalsIgnoreCase("MULTIPLE_INVITES")) {
                p.sendMessage(plugin.msg().prefix() + "You have multiple invites. Use /team invites then /team accept <team>.");
                return true;
            }

            p.sendMessage(plugin.msg().prefix() + msg);
            return true;
        } catch (Exception ex) {
            p.sendMessage(plugin.msg().prefix() + "An error occurred.");
            plugin.getLogger().severe("Command error: " + ex.getMessage());
            return true;
        }
    }

    private Optional<UUID> resolveInviteTeamIdByName(UUID invitee, String teamArgRaw) {
        String wanted = normalize(teamArgRaw);

        for (TeamInvite inv : plugin.teams().getInvites(invitee)) {
            String teamName = plugin.teams().getTeamById(inv.teamId())
                    .map(Team::getName)
                    .orElse("Team");

            if (normalize(teamName).equalsIgnoreCase(wanted)) {
                return Optional.of(inv.teamId());
            }
        }
        return Optional.empty();
    }

    private String normalize(String s) {
        String colored = Msg.color(s);
        String stripped = ChatColor.stripColor(colored);
        return stripped == null ? "" : stripped.trim().toLowerCase(Locale.ROOT);
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();

        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();

        return uuid.toString().substring(0, 8);
    }
}
