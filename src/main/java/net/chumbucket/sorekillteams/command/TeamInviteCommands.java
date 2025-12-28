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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

import static net.chumbucket.sorekillteams.util.CommandUtil.nameOf;
import static net.chumbucket.sorekillteams.util.CommandUtil.normalize;

public final class TeamInviteCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    public TeamInviteCommands(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        return switch (sub) {
            case "invite" -> handleInvite(p, args, debug);
            case "invites" -> handleInvites(p);               // ✅ UPDATED
            case "accept" -> handleAccept(p, args, debug);
            case "deny" -> handleDeny(p, args, debug);
            default -> false;
        };
    }

    // ---------------------------------------------------------------------
    // /team invite <player>
    // ---------------------------------------------------------------------

    private boolean handleInvite(Player p, String[] args, boolean debug) {
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

        if (plugin.actionbar() != null) {
            plugin.actionbar().send(target, "actionbar.team_invite_received",
                    "{inviter}", p.getName(),
                    "{team}", Msg.color(teamName)
            );
        }

        if (debug) {
            plugin.getLogger().info("[TEAM-DBG] " + p.getName()
                    + " invited " + target.getName()
                    + " team=" + teamName);
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // /team invites
    // ---------------------------------------------------------------------

    private boolean handleInvites(Player p) {
        if (!p.hasPermission("sorekillteams.invites")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        // ✅ GUI path
        if (plugin.menus() != null && plugin.menus().enabledInConfigYml()) {
            plugin.menus().open(p, "invites");
            return true;
        }

        // Fallback: chat list
        Collection<TeamInvite> invs = plugin.teams().getInvites(p.getUniqueId());
        if (invs == null || invs.isEmpty()) {
            plugin.msg().send(p, "team_invites_none");
            return true;
        }

        List<TeamInvite> sorted = invs.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(TeamInvite::getExpiresAtMs))
                .toList();

        plugin.msg().send(p, "team_invites_header");

        long now = System.currentTimeMillis();
        for (TeamInvite inv : sorted) {
            String teamName = plugin.teams().getTeamById(inv.getTeamId())
                    .map(Team::getName)
                    .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

            plugin.msg().send(p, "team_invites_entry",
                    "{team}", Msg.color(teamName),
                    "{inviter}", nameOf(inv.getInviter()),
                    "{seconds}", String.valueOf(inv.getSecondsRemaining(now))
            );
        }

        if (sorted.size() > 1) {
            plugin.msg().send(p, "team_invites_tip");
        }

        return true;
    }

    // ---------------------------------------------------------------------
    // /team accept [team]
    // ---------------------------------------------------------------------

    private boolean handleAccept(Player p, String[] args, boolean debug) {
        if (!p.hasPermission("sorekillteams.accept")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        Optional<UUID> teamId = Optional.empty();
        if (args.length >= 2) {
            String teamArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            teamId = resolveInviteTeamId(p.getUniqueId(), teamArg);
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

        plugin.msg().send(p, "team_joined",
                "{team}", Msg.color(teamName)
        );
        plugin.msg().send(p, "team_joined_who",
                "{inviter}", nameOf(inv.getInviter())
        );

        Player inviterOnline = Bukkit.getPlayer(inv.getInviter());
        if (inviterOnline != null) {
            plugin.msg().send(inviterOnline, "team_invite_accepted_inviter",
                    "{player}", p.getName(),
                    "{team}", Msg.color(teamName)
            );
        }

        if (debug) {
            plugin.getLogger().info("[TEAM-DBG] " + p.getName()
                    + " accepted invite team=" + teamName);
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // /team deny [team]
    // ---------------------------------------------------------------------

    private boolean handleDeny(Player p, String[] args, boolean debug) {
        if (!p.hasPermission("sorekillteams.deny")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        Optional<UUID> teamId = Optional.empty();
        if (args.length >= 2) {
            String teamArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            teamId = resolveInviteTeamId(p.getUniqueId(), teamArg);
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

        if (debug) {
            plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " denied invite");
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Resolves a GUI or user-supplied argument into a team UUID.
     * Accepts either:
     * - UUID (from GUI: {invite_team_id})
     * - Team name (typed by player)
     */
    private Optional<UUID> resolveInviteTeamId(UUID invitee, String rawArg) {
        if (invitee == null || rawArg == null) return Optional.empty();

        String raw = rawArg.trim();
        if (raw.isEmpty()) return Optional.empty();

        // UUID path (GUI)
        try {
            UUID id = UUID.fromString(raw);
            for (TeamInvite inv : plugin.teams().getInvites(invitee)) {
                if (inv != null && id.equals(inv.getTeamId())) {
                    return Optional.of(id);
                }
            }
        } catch (Exception ignored) {}

        // Name path (chat)
        String wanted = normalize(raw);
        for (TeamInvite inv : plugin.teams().getInvites(invitee)) {
            if (inv == null) continue;

            String teamName = plugin.teams().getTeamById(inv.getTeamId())
                    .map(Team::getName)
                    .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

            if (normalize(teamName).equalsIgnoreCase(wanted)) {
                return Optional.of(inv.getTeamId());
            }
        }
        return Optional.empty();
    }
}