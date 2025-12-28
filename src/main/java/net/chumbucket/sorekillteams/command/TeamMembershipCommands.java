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
import net.chumbucket.sorekillteams.util.TeamNameValidator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.UUID;

import static net.chumbucket.sorekillteams.util.CommandUtil.nameOf;
import static net.chumbucket.sorekillteams.util.CommandUtil.resolvePlayerUuidOnlineOrUuid;

public final class TeamMembershipCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;
    private final TeamNameValidator nameValidator;

    public TeamMembershipCommands(SorekillTeamsPlugin plugin, TeamNameValidator nameValidator) {
        this.plugin = plugin;
        this.nameValidator = nameValidator;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        return switch (sub) {
            case "leave" -> handleLeave(p, debug);
            case "disband" -> handleDisband(p, debug);
            case "rename" -> handleRename(p, args, debug);
            case "kick" -> handleKick(p, args, debug);
            case "transfer" -> handleTransfer(p, args, debug);
            default -> false;
        };
    }

    private boolean handleLeave(Player p, boolean debug) {
        if (!p.hasPermission("sorekillteams.leave")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        plugin.teams().leaveTeam(p.getUniqueId());
        plugin.msg().send(p, "team_left");

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " left team");
        return true;
    }

    private boolean handleDisband(Player p, boolean debug) {
        if (!p.hasPermission("sorekillteams.disband")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        plugin.teams().disbandTeam(p.getUniqueId());

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " disbanded team");
        return true;
    }

    private boolean handleRename(Player p, String[] args, boolean debug) {
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

    private boolean handleKick(Player p, String[] args, boolean debug) {
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

    private boolean handleTransfer(Player p, String[] args, boolean debug) {
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
}
