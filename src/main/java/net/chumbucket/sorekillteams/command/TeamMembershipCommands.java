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
import net.chumbucket.sorekillteams.network.TeamEventPacket;
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

        // Capture before leaving for network event
        Team before = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        UUID teamId = before != null ? before.getId() : null;
        String teamName = before != null ? before.getName() : "Team";

        plugin.teams().leaveTeam(p.getUniqueId());
        plugin.msg().send(p, "team_left");

        // ✅ Cross-server event (so other backends broadcast)
        publishTeamEventIfReady(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.MEMBER_LEFT,
                teamId,
                teamName,
                p.getUniqueId(),
                safeName(p.getName()),
                p.getUniqueId(), // target = leaver
                safeName(p.getName()),
                System.currentTimeMillis()
        ));

        reopenAfterMembershipChange(p);

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " left team");
        return true;
    }

    private boolean handleDisband(Player p, boolean debug) {
        if (!p.hasPermission("sorekillteams.disband")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        // ✅ Capture team before disband (after action it may be gone)
        Team before = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        UUID teamId = before != null ? before.getId() : null;
        String teamName = before != null ? before.getName() : "Team";
        UUID owner = p.getUniqueId();
        String ownerName = safeName(p.getName());

        plugin.teams().disbandTeam(owner);

        // ✅ Cross-server disband event (command path was missing this)
        publishTeamEventIfReady(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.TEAM_DISBANDED,
                teamId,
                teamName,
                owner,
                ownerName,
                null,
                "",
                System.currentTimeMillis()
        ));

        reopenMainAfterDisband(p);

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
        UUID teamId = before != null ? before.getId() : null;
        String oldName = before != null ? before.getName() : "Team";

        plugin.teams().renameTeam(p.getUniqueId(), v.plainName());

        Team after = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        String newName = after != null ? after.getName() : v.plainName();

        plugin.msg().send(p, "team_renamed",
                "{old}", Msg.color(oldName),
                "{team}", Msg.color(newName)
        );

        // ✅ Cross-server rename event: store old name in targetName (matches your onRemoteTeamEvent mapping)
        publishTeamEventIfReady(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.TEAM_RENAMED,
                teamId,
                newName,
                p.getUniqueId(),
                safeName(p.getName()),
                null,
                oldName, // targetName used as "{old}" on remote
                System.currentTimeMillis()
        ));

        reopenTeamInfoIfInMenu(p);

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

        // ✅ Upgrade: allow offline player names too (not only online/uuid)
        UUID targetUuid = resolvePlayerUuidOnlineOrUuid(args[1]);
        if (targetUuid == null) {
            targetUuid = resolveOfflineUuidByName(args[1]);
        }
        if (targetUuid == null) {
            plugin.msg().send(p, "team_player_must_be_online_or_uuid");
            return true;
        }

        if (targetUuid.equals(p.getUniqueId())) {
            plugin.msg().send(p, "team_cannot_kick_self");
            return true;
        }

        // ✅ Capture team snapshot BEFORE kick (membership may change)
        Team before = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        UUID teamId = before != null ? before.getId() : null;
        String teamName = (before != null ? before.getName() : "Team");

        // Capture target display name BEFORE kick too
        String targetName = safeName(nameOf(targetUuid));

        plugin.teams().kickMember(p.getUniqueId(), targetUuid);

        plugin.msg().send(p, "team_kick_success",
                "{player}", targetName,
                "{team}", Msg.color(teamName)
        );

        // Local notify if on same backend
        Player targetOnline = Bukkit.getPlayer(targetUuid);
        if (targetOnline != null && targetOnline.isOnline()) {
            plugin.msg().send(targetOnline, "team_kick_target",
                    "{team}", Msg.color(teamName),
                    "{by}", p.getName()
            );

            try { plugin.ensureTeamFreshFromSql(targetUuid); } catch (Throwable ignored) {}
        }

        // ✅ Cross-server event so:
        // - other backends broadcast to teammates
        // - the kicked player gets notified on their backend via plugin.onRemoteTeamEvent direct-target logic
        publishTeamEventIfReady(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.MEMBER_KICKED,
                teamId,
                teamName,
                p.getUniqueId(),
                safeName(p.getName()),
                targetUuid,
                targetName,
                System.currentTimeMillis()
        ));

        reopenTeamInfoIfInMenu(p);

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
            targetUuid = resolveOfflineUuidByName(args[1]);
        }
        if (targetUuid == null) {
            plugin.msg().send(p, "team_player_must_be_online_or_uuid");
            return true;
        }

        // Capture team before transfer
        Team before = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        UUID teamId = before != null ? before.getId() : null;
        String teamName = before != null ? before.getName() : "Team";

        plugin.teams().transferOwnership(p.getUniqueId(), targetUuid);

        String targetName = safeName(nameOf(targetUuid));

        plugin.msg().send(p, "team_transfer_success",
                "{player}", targetName,
                "{team}", Msg.color(teamName)
        );

        Player targetOnline = Bukkit.getPlayer(targetUuid);
        if (targetOnline != null && targetOnline.isOnline()) {
            plugin.msg().send(targetOnline, "team_transfer_received",
                    "{team}", Msg.color(teamName),
                    "{by}", p.getName()
            );
        }

        // ✅ Cross-server transfer event: targetName becomes new owner name on remote broadcast
        publishTeamEventIfReady(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.OWNER_TRANSFERRED,
                teamId,
                teamName,
                p.getUniqueId(),
                safeName(p.getName()),
                targetUuid,
                targetName,
                System.currentTimeMillis()
        ));

        reopenTeamInfoIfInMenu(p);
        if (targetOnline != null) reopenTeamInfoIfInMenu(targetOnline);

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " transferred ownership -> " + targetUuid);
        return true;
    }

    // ---------------------------------------------------------
    // Team event publish helpers
    // ---------------------------------------------------------

    private void publishTeamEventIfReady(TeamEventPacket pkt) {
        if (pkt == null) return;
        // teamId is mandatory for routing broadcasts on remote
        if (pkt.teamId() == null) return;
        plugin.publishTeamEvent(pkt);
    }

    private static String safeName(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------
    // Offline name resolution
    // ---------------------------------------------------------

    private UUID resolveOfflineUuidByName(String nameOrUuid) {
        if (nameOrUuid == null) return null;
        String s = nameOrUuid.trim();
        if (s.isEmpty()) return null;

        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {}

        UUID cached = getOfflinePlayerUuidIfCached(s);
        if (cached != null) return cached;

        return getOfflinePlayerUuidIfHasPlayedBefore(s);
    }

    private UUID getOfflinePlayerUuidIfCached(String name) {
        try {
            java.lang.reflect.Method m = Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class);
            Object off = m.invoke(null, name);
            if (off instanceof org.bukkit.OfflinePlayer op) {
                UUID id = op.getUniqueId();
                if (id != null) return id;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    @SuppressWarnings("deprecation")
    private UUID getOfflinePlayerUuidIfHasPlayedBefore(String name) {
        try {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op == null) return null;

            if (!op.hasPlayedBefore() && !op.isOnline()) return null;

            return op.getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------
    // Menu refresh helpers (safe, no-op if menus disabled)
    // ---------------------------------------------------------

    private void reopenAfterMembershipChange(Player p) {
        if (p == null) return;

        boolean menusEnabled = plugin.getConfig().getBoolean("menus.enabled", true);
        if (!menusEnabled || plugin.menuRouter() == null) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean inTeam = plugin.teams().getTeamByPlayer(p.getUniqueId()).isPresent();
            if (inTeam) plugin.menuRouter().open(p, "team_info");
            else plugin.menuRouter().open(p, "main");
        });
    }

    private void reopenMainAfterDisband(Player p) {
        if (p == null) return;

        boolean menusEnabled = plugin.getConfig().getBoolean("menus.enabled", true);
        if (!menusEnabled || plugin.menuRouter() == null) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.menuRouter().open(p, "main"));
    }

    private void reopenTeamInfoIfInMenu(Player p) {
        if (p == null) return;

        boolean menusEnabled = plugin.getConfig().getBoolean("menus.enabled", true);
        if (!menusEnabled || plugin.menuRouter() == null) return;

        if (p.getOpenInventory() == null) return;
        if (p.getOpenInventory().getTopInventory() == null) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory() == null || p.getOpenInventory().getTopInventory() == null) return;
            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof net.chumbucket.sorekillteams.menu.MenuHolder)) return;

            boolean inTeam = plugin.teams().getTeamByPlayer(p.getUniqueId()).isPresent();
            if (inTeam) plugin.menuRouter().open(p, "team_info");
            else plugin.menuRouter().open(p, "main");
        });
    }
}
