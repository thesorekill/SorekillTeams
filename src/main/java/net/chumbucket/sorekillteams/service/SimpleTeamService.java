/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.service;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamInvite;
import net.chumbucket.sorekillteams.model.TeamInvites;
import net.chumbucket.sorekillteams.network.InvitePacket;
import net.chumbucket.sorekillteams.network.TeamChatPacket;
import net.chumbucket.sorekillteams.network.TeamEventPacket;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.storage.sql.SqlTeamInviteStorage;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SimpleTeamService implements TeamService {

    private static final String MAX_PERM_PREFIX = "sorekillteams.max.";
    private static final String SPY_PERMISSION = "sorekillteams.spy";

    private final SorekillTeamsPlugin plugin;
    private final TeamStorage storage;

    // YAML mode invite store (kept for compatibility)
    private final TeamInvites invites;

    private final Map<UUID, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();

    private final Set<UUID> teamChatToggled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> inviteCooldownUntil = new ConcurrentHashMap<>();

    // spyPlayer -> set of teamIds being spied
    private final Map<UUID, Set<UUID>> spyTargets = new ConcurrentHashMap<>();

    // ✅ Only write to SQL when THIS backend actually changed something
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public SimpleTeamService(SorekillTeamsPlugin plugin, TeamStorage storage) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.invites = Objects.requireNonNull(plugin.invites(), "invites");
    }

    private boolean isSqlMode() {
        return !"yaml".equalsIgnoreCase(plugin.storageTypeActive()) && plugin.sqlInvites() != null;
    }

    private SqlTeamInviteStorage sqlInvites() {
        return plugin.sqlInvites();
    }

    // =========================
    // Dirty tracking
    // =========================

    public void markDirty() { dirty.set(true); }
    public boolean isDirty() { return dirty.get(); }
    public boolean consumeDirty() { return dirty.getAndSet(false); }

    // =========================
    // Cache hygiene / SQL refresh support
    // =========================

    public void clearCachedMembership(UUID playerUuid) {
        if (playerUuid == null) return;

        playerToTeam.remove(playerUuid);
        teamChatToggled.remove(playerUuid);
        invites.clearTarget(playerUuid);
    }

    public void evictCachedTeam(UUID teamId) {
        if (teamId == null) return;

        Team removed = teams.remove(teamId);
        if (removed == null) {
            removeTeamFromAllSpyTargets(teamId);
            return;
        }

        for (UUID m : new HashSet<>(removed.getMembers())) {
            if (m == null) continue;
            UUID mapped = playerToTeam.get(m);
            if (teamId.equals(mapped)) {
                playerToTeam.remove(m);
                teamChatToggled.remove(m);
                invites.clearTarget(m);
            }
        }

        removeTeamFromAllSpyTargets(teamId);
    }

    public void replaceTeamsSnapshot(Collection<Team> loadedTeams) {
        Map<UUID, Team> newTeams = new HashMap<>();
        Map<UUID, UUID> newPlayerToTeam = new HashMap<>();

        if (loadedTeams != null) {
            for (Team t : loadedTeams) {
                if (t == null || t.getId() == null) continue;

                ensureOwnerInMembers(t);
                dedupeMembers(t);

                newTeams.put(t.getId(), t);
                for (UUID m : t.getMembers()) {
                    if (m != null) newPlayerToTeam.put(m, t.getId());
                }
            }
        }

        /*
         * IMPORTANT:
         * Snapshots must be SILENT.
         *
         * Previously we broadcasted "team disbanded" here when a team disappeared
         * between snapshots. In SQL mode, that causes a double message because:
         *  - the command/service broadcasts immediately, AND
         *  - the SQL refresh removes the team and this diff broadcast fires again.
         *
         * Disband messaging is now strictly event-driven:
         *  - Origin server sends local message during disbandTeam/adminDisbandTeam
         *  - Other servers show the message when they receive Redis TEAM_DISBANDED
         */
        // (no broadcast on snapshot diff)

        teams.clear();
        teams.putAll(newTeams);

        playerToTeam.clear();
        playerToTeam.putAll(newPlayerToTeam);

        teamChatToggled.removeIf(u -> !playerToTeam.containsKey(u));

        spyTargets.entrySet().removeIf(e -> {
            Set<UUID> watching = e.getValue();
            if (watching == null) return true;
            watching.removeIf(id -> !teams.containsKey(id));
            return watching.isEmpty();
        });

        dirty.set(false);
    }

    // =========================
    // Storage helpers
    // =========================

    public void putLoadedTeam(Team t) {
        if (t == null || t.getId() == null) return;

        ensureOwnerInMembers(t);
        dedupeMembers(t);

        teams.put(t.getId(), t);
        for (UUID m : t.getMembers()) {
            if (m != null) playerToTeam.put(m, t.getId());
        }
    }

    public void putAllTeams(Collection<Team> loadedTeams) {
        replaceTeamsSnapshot(loadedTeams);
    }

    public Collection<Team> allTeams() {
        return new ArrayList<>(teams.values());
    }

    // =========================
    // Lookup
    // =========================

    @Override
    public Optional<Team> getTeamByPlayer(UUID player) {
        if (player == null) return Optional.empty();
        UUID id = playerToTeam.get(player);
        return (id == null) ? Optional.empty() : Optional.ofNullable(teams.get(id));
    }

    @Override
    public Optional<Team> getTeamById(UUID teamId) {
        if (teamId == null) return Optional.empty();
        return Optional.ofNullable(teams.get(teamId));
    }

    @Override
    public Optional<Team> getTeamByName(String teamName) {
        if (teamName == null) return Optional.empty();
        String norm = normalizeForCompare(teamName);
        if (norm.isBlank()) return Optional.empty();

        for (Team t : teams.values()) {
            if (t == null) continue;
            String n = t.getName();
            if (n == null) continue;

            if (normalizeForCompare(n).equals(norm)) return Optional.of(t);
        }
        return Optional.empty();
    }

    // =========================
    // Core actions
    // =========================

    @Override
    public Team createTeam(UUID owner, String name) {
        if (owner == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");
        if (playerToTeam.containsKey(owner)) {
            throw new TeamServiceException(TeamError.ALREADY_IN_TEAM, "team_already_in_team");
        }

        final String cleanName = normalizeTeamNameOrThrow(name);

        if (teamNameTaken(cleanName)) {
            throw new TeamServiceException(TeamError.TEAM_NAME_TAKEN, "team_name_taken");
        }

        UUID id = UUID.randomUUID();
        Team t = new Team(id, cleanName, owner);

        ensureOwnerInMembers(t);
        dedupeMembers(t);

        teams.put(id, t);
        playerToTeam.put(owner, id);

        markDirty();
        safeSave();
        return t;
    }

    @Override
    public void disbandTeam(UUID owner) {
        if (owner == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) {
            throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");
        }

        broadcastToTeam(t, plugin.msg().format(
                "team_team_disbanded",
                "{team}", Msg.color(t.getName())
        ));

        internalDisbandTeam(t);

        markDirty();
        safeSave();

        if (isSqlMode()) {
            try { sqlInvites().deleteAllForTeam(t.getId()); } catch (Exception ignored) {}
        }

        // ✅ Redis: team disbanded
        // FIX: keep constructor arg-shape consistent with the rest of the codebase (targetUuid + targetName present)
        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.TEAM_DISBANDED,
                t.getId(),
                t.getName(),
                owner,
                safeName(nameOf(owner)),
                null,
                "",
                System.currentTimeMillis()
        ));
    }

    @Override
    public void leaveTeam(UUID player) {
        if (player == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");

        Team t = getTeamByPlayer(player).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (t.getOwner().equals(player)) {
            throw new TeamServiceException(TeamError.OWNER_CANNOT_LEAVE, "team_owner_cannot_leave");
        }

        t.getMembers().remove(player);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.remove(player);
        teamChatToggled.remove(player);
        invites.clearTarget(player);

        markDirty();
        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_member_left",
                "{player}", nameOf(player),
                "{team}", Msg.color(t.getName())
        ));

        // ✅ Redis: member left
        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.MEMBER_LEFT,
                t.getId(),
                t.getName(),
                player,
                safeName(nameOf(player)),
                player,
                safeName(nameOf(player)),
                System.currentTimeMillis()
        ));
    }

    @Override
    public void invite(UUID inviter, UUID invitee) {
        if (inviter == null || invitee == null) {
            throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");
        }
        if (inviter.equals(invitee)) {
            throw new TeamServiceException(TeamError.INVITE_SELF, "team_invite_self");
        }

        Team t = getTeamByPlayer(inviter).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(inviter)) {
            throw new TeamServiceException(TeamError.ONLY_OWNER_CAN_INVITE, "team_not_owner");
        }

        int max = getTeamMaxMembers(t);
        if (uniqueMemberCount(t) >= max) {
            throw new TeamServiceException(TeamError.TEAM_FULL, "team_team_full");
        }

        if (t.isMember(invitee)) {
            throw new TeamServiceException(TeamError.ALREADY_MEMBER, "team_already_member");
        }
        if (playerToTeam.containsKey(invitee)) {
            throw new TeamServiceException(TeamError.INVITEE_IN_TEAM, "team_invitee_in_team");
        }

        long now = System.currentTimeMillis();

        int cdSeconds = Math.max(0, plugin.getConfig().getInt("invites.cooldown_seconds", 10));
        if (cdSeconds > 0) {
            long until = inviteCooldownUntil.getOrDefault(inviter, 0L);
            if (until > now) {
                long remaining = (until - now + 999) / 1000;
                throw new TeamServiceException(
                        TeamError.INVITE_COOLDOWN,
                        "team_invite_cooldown",
                        "{seconds}", String.valueOf(remaining)
                );
            }
            inviteCooldownUntil.put(inviter, now + (cdSeconds * 1000L));
        }

        int expirySeconds = Math.max(1, plugin.getConfig().getInt("invites.expiry_seconds", 300));
        long expiresAt = now + (expirySeconds * 1000L);

        String inviterName = safeName(nameOf(inviter));
        String inviteeName = safeName(nameOf(invitee));

        if (isSqlMode()) {
            SqlTeamInviteStorage sql = sqlInvites();

            try {
                int maxPending = Math.max(1, plugin.getConfig().getInt("invites.max_pending_per_player", 5));
                int pendingNow = sql.pendingForTarget(invitee, now);
                if (pendingNow >= maxPending) {
                    throw new TeamServiceException(TeamError.INVITE_TARGET_MAX_PENDING, "team_invite_target_max_pending");
                }

                int maxOutgoing = Math.max(1, plugin.getConfig().getInt("invites.max_outgoing_per_team", 10));
                int outgoingNow = sql.outgoingForTeam(t.getId(), now);
                if (outgoingNow >= maxOutgoing) {
                    throw new TeamServiceException(TeamError.INVITE_TEAM_MAX_OUTGOING, "team_invite_team_max_outgoing");
                }

                boolean allowMultiTeams = plugin.getConfig().getBoolean("invites.allow_multiple_from_different_teams", true);
                if (!allowMultiTeams && sql.hasInviteFromOtherTeam(invitee, t.getId(), now)) {
                    throw new TeamServiceException(TeamError.INVITE_ONLY_ONE_TEAM, "team_invite_only_one_team");
                }

                sql.upsert(t.getId(), inviter, invitee, now, expiresAt);

                plugin.publishInvite(new InvitePacket(
                        plugin.networkServerName(),
                        InvitePacket.Type.SENT,
                        t.getId(),
                        t.getName(),
                        inviter,
                        inviterName,
                        invitee,
                        inviteeName,
                        now,
                        expiresAt
                ));
                return;

            } catch (TeamServiceException te) {
                throw te;
            } catch (Exception e) {
                plugin.getLogger().warning("SQL invite failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        int maxPending = Math.max(1, plugin.getConfig().getInt("invites.max_pending_per_player", 5));
        int pendingNow = invites.pendingForTarget(invitee, now);
        if (pendingNow >= maxPending) {
            throw new TeamServiceException(TeamError.INVITE_TARGET_MAX_PENDING, "team_invite_target_max_pending");
        }

        int maxOutgoing = Math.max(1, plugin.getConfig().getInt("invites.max_outgoing_per_team", 10));
        int outgoingNow = invites.outgoingForTeam(t.getId(), now);
        if (outgoingNow >= maxOutgoing) {
            throw new TeamServiceException(TeamError.INVITE_TEAM_MAX_OUTGOING, "team_invite_team_max_outgoing");
        }

        boolean allowMultiTeams = plugin.getConfig().getBoolean("invites.allow_multiple_from_different_teams", true);
        if (!allowMultiTeams && invites.hasInviteFromOtherTeam(invitee, t.getId(), now)) {
            throw new TeamServiceException(TeamError.INVITE_ONLY_ONE_TEAM, "team_invite_only_one_team");
        }

        TeamInvite inv = new TeamInvite(
                t.getId(),
                t.getName(),
                inviter,
                invitee,
                now,
                expiresAt
        );

        boolean refreshOnReinvite = plugin.getConfig().getBoolean("invites.reinvite_refreshes_expiry", true);
        if (refreshOnReinvite) {
            boolean refreshed = invites.refresh(invitee, t.getId(), inv, now);
            if (refreshed) {
                plugin.publishInvite(new InvitePacket(
                        plugin.networkServerName(),
                        InvitePacket.Type.SENT,
                        t.getId(),
                        t.getName(),
                        inviter,
                        inviterName,
                        invitee,
                        inviteeName,
                        now,
                        expiresAt
                ));
                return;
            }
        }

        boolean created = invites.create(inv, now);
        if (!created) {
            throw new TeamServiceException(TeamError.INVITE_ALREADY_PENDING, "team_invite_already_pending");
        }

        plugin.publishInvite(new InvitePacket(
                plugin.networkServerName(),
                InvitePacket.Type.SENT,
                t.getId(),
                t.getName(),
                inviter,
                inviterName,
                invitee,
                inviteeName,
                now,
                expiresAt
        ));
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "";
        return name;
    }

    @Override
    public Optional<TeamInvite> acceptInvite(UUID invitee, Optional<UUID> teamId) {
        if (invitee == null) return Optional.empty();

        long now = System.currentTimeMillis();

        if (playerToTeam.containsKey(invitee)) {
            invites.clearTarget(invitee);
            throw new TeamServiceException(TeamError.ALREADY_IN_TEAM, "team_already_in_team");
        }

        if (isSqlMode()) {
            SqlTeamInviteStorage sql = sqlInvites();

            List<TeamInvite> active;
            try {
                active = sql.listActive(invitee, now, id -> {
                    Team t = teams.get(id);
                    return (t == null ? "Team" : t.getName());
                });
            } catch (Exception e) {
                plugin.getLogger().warning("SQL invite list failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                active = List.of();
            }

            if (active.isEmpty()) return Optional.empty();

            final TeamInvite inv;
            if (teamId != null && teamId.isPresent()) {
                UUID id = teamId.get();
                inv = active.stream().filter(i -> i.getTeamId().equals(id)).findFirst().orElse(null);
                if (inv == null) return Optional.empty();
            } else {
                if (active.size() > 1) {
                    throw new TeamServiceException(TeamError.MULTIPLE_INVITES, "team_multiple_invites_hint");
                }
                inv = active.get(0);
            }

            Team t = teams.get(inv.getTeamId());
            if (t == null) {
                try { sql.delete(invitee, inv.getTeamId()); } catch (Exception ignored) {}
                throw new TeamServiceException(TeamError.INVITE_EXPIRED, "team_invite_expired");
            }

            ensureOwnerInMembers(t);
            dedupeMembers(t);

            int max = getTeamMaxMembers(t);
            if (uniqueMemberCount(t) >= max) {
                throw new TeamServiceException(TeamError.TEAM_FULL, "team_team_full");
            }

            t.getMembers().add(invitee);
            ensureOwnerInMembers(t);
            dedupeMembers(t);

            playerToTeam.put(invitee, t.getId());

            try { sql.delete(invitee, inv.getTeamId()); } catch (Exception ignored) {}

            markDirty();
            safeSave();

            broadcastToTeam(t, plugin.msg().format(
                    "team_member_joined",
                    "{player}", nameOf(invitee),
                    "{team}", Msg.color(t.getName())
            ));

            plugin.publishInvite(new InvitePacket(
                    plugin.networkServerName(),
                    InvitePacket.Type.ACCEPTED,
                    t.getId(),
                    t.getName(),
                    inv.getInviter(),
                    safeName(nameOf(inv.getInviter())),
                    invitee,
                    safeName(nameOf(invitee)),
                    now,
                    0L
            ));

            // ✅ Redis: member joined
            publishTeamEvent(new TeamEventPacket(
                    plugin.networkServerName(),
                    TeamEventPacket.Type.MEMBER_JOINED,
                    t.getId(),
                    t.getName(),
                    invitee,
                    safeName(nameOf(invitee)),
                    invitee,
                    safeName(nameOf(invitee)),
                    now
            ));

            return Optional.of(inv);
        }

        List<TeamInvite> active = invites.listActive(invitee, now);
        if (active.isEmpty()) return Optional.empty();

        final TeamInvite inv;
        if (teamId != null && teamId.isPresent()) {
            UUID id = teamId.get();
            inv = invites.get(invitee, id, now).orElse(null);
            if (inv == null) return Optional.empty();
        } else {
            if (active.size() > 1) {
                throw new TeamServiceException(TeamError.MULTIPLE_INVITES, "team_multiple_invites_hint");
            }
            inv = active.get(0);
        }

        if (inv.isExpired(now)) {
            invites.remove(invitee, inv.getTeamId());
            throw new TeamServiceException(TeamError.INVITE_EXPIRED, "team_invite_expired");
        }

        Team t = teams.get(inv.getTeamId());
        if (t == null) {
            invites.remove(invitee, inv.getTeamId());
            throw new TeamServiceException(TeamError.INVITE_EXPIRED, "team_invite_expired");
        }

        ensureOwnerInMembers(t);
        dedupeMembers(t);

        int max = getTeamMaxMembers(t);
        if (uniqueMemberCount(t) >= max) {
            throw new TeamServiceException(TeamError.TEAM_FULL, "team_team_full");
        }

        t.getMembers().add(invitee);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.put(invitee, t.getId());
        invites.remove(invitee, inv.getTeamId());

        markDirty();
        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_member_joined",
                "{player}", nameOf(invitee),
                "{team}", Msg.color(t.getName())
        ));

        plugin.publishInvite(new InvitePacket(
                plugin.networkServerName(),
                InvitePacket.Type.ACCEPTED,
                t.getId(),
                t.getName(),
                inv.getInviter(),
                safeName(nameOf(inv.getInviter())),
                invitee,
                safeName(nameOf(invitee)),
                now,
                0L
        ));

        // ✅ Redis: member joined
        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.MEMBER_JOINED,
                t.getId(),
                t.getName(),
                invitee,
                safeName(nameOf(invitee)),
                invitee,
                safeName(nameOf(invitee)),
                now
        ));

        return Optional.of(inv);
    }

    @Override
    public boolean denyInvite(UUID invitee, Optional<UUID> teamId) {
        if (invitee == null) return false;

        long now = System.currentTimeMillis();

        if (isSqlMode()) {
            SqlTeamInviteStorage sql = sqlInvites();

            List<TeamInvite> active;
            try {
                active = sql.listActive(invitee, now, id -> {
                    Team t = teams.get(id);
                    return (t == null ? "Team" : t.getName());
                });
            } catch (Exception e) {
                plugin.getLogger().warning("SQL invite list failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                active = List.of();
            }

            if (active.isEmpty()) return false;

            TeamInvite invToDeny;
            if (teamId != null && teamId.isPresent()) {
                UUID id = teamId.get();
                invToDeny = active.stream().filter(i -> i.getTeamId().equals(id)).findFirst().orElse(null);
                if (invToDeny == null) return false;
            } else {
                if (active.size() > 1) {
                    throw new TeamServiceException(TeamError.MULTIPLE_INVITES, "team_multiple_invites_hint");
                }
                invToDeny = active.get(0);
            }

            boolean removed = false;
            try { removed = sql.delete(invitee, invToDeny.getTeamId()); } catch (Exception ignored) {}

            if (removed) {
                Team t = teams.get(invToDeny.getTeamId());
                String teamName = (t != null) ? t.getName() : invToDeny.getTeamName();

                plugin.publishInvite(new InvitePacket(
                        plugin.networkServerName(),
                        InvitePacket.Type.DENIED,
                        invToDeny.getTeamId(),
                        teamName,
                        invToDeny.getInviter(),
                        safeName(nameOf(invToDeny.getInviter())),
                        invitee,
                        safeName(nameOf(invitee)),
                        now,
                        0L
                ));
            }

            return removed;
        }

        List<TeamInvite> active = invites.listActive(invitee, now);
        if (active.isEmpty()) {
            invites.clearTarget(invitee);
            return false;
        }

        final TeamInvite invToDeny;
        if (teamId != null && teamId.isPresent()) {
            invToDeny = invites.get(invitee, teamId.get(), now).orElse(null);
            if (invToDeny == null) return false;
        } else {
            if (active.size() > 1) {
                throw new TeamServiceException(TeamError.MULTIPLE_INVITES, "team_multiple_invites_hint");
            }
            invToDeny = active.get(0);
        }

        boolean removed = invites.remove(invitee, invToDeny.getTeamId());
        if (removed) {
            Team t = teams.get(invToDeny.getTeamId());
            String teamName = (t != null) ? t.getName() : invToDeny.getTeamName();

            plugin.publishInvite(new InvitePacket(
                    plugin.networkServerName(),
                    InvitePacket.Type.DENIED,
                    invToDeny.getTeamId(),
                    teamName,
                    invToDeny.getInviter(),
                    safeName(nameOf(invToDeny.getInviter())),
                    invitee,
                    safeName(nameOf(invitee)),
                    now,
                    0L
            ));
        }

        return removed;
    }

    @Override
    public Collection<TeamInvite> getInvites(UUID invitee) {
        if (invitee == null) return List.of();
        long now = System.currentTimeMillis();

        if (isSqlMode()) {
            try {
                return sqlInvites().listActive(invitee, now, id -> {
                    Team t = teams.get(id);
                    return (t == null ? "Team" : t.getName());
                });
            } catch (Exception e) {
                plugin.getLogger().warning("SQL invite list failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return List.of();
            }
        }

        return invites.listActive(invitee, now);
    }

    @Override
    public boolean areTeammates(UUID a, UUID b) {
        if (a == null || b == null) return false;
        UUID ta = playerToTeam.get(a);
        UUID tb = playerToTeam.get(b);
        return ta != null && ta.equals(tb);
    }

    @Override
    public void kickMember(UUID owner, UUID member) {
        if (owner == null || member == null) {
            throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");
        }

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) {
            throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");
        }

        if (member.equals(t.getOwner())) {
            throw new TeamServiceException(TeamError.CANNOT_KICK_OWNER, "team_cannot_kick_owner");
        }
        if (!t.isMember(member)) {
            throw new TeamServiceException(TeamError.TARGET_NOT_MEMBER, "team_target_not_member");
        }

        t.getMembers().remove(member);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.remove(member);
        teamChatToggled.remove(member);
        invites.clearTarget(member);

        markDirty();
        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_member_kicked_broadcast",
                "{player}", nameOf(member),
                "{team}", Msg.color(t.getName())
        ));

        // ✅ Redis: member kicked
        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.MEMBER_KICKED,
                t.getId(),
                t.getName(),
                owner,
                safeName(nameOf(owner)),
                member,
                safeName(nameOf(member)),
                System.currentTimeMillis()
        ));
    }

    @Override
    public void transferOwnership(UUID owner, UUID newOwner) {
        if (owner == null || newOwner == null) {
            throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");
        }

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) {
            throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");
        }

        if (owner.equals(newOwner)) {
            throw new TeamServiceException(TeamError.TRANSFER_SELF, "team_transfer_self");
        }
        if (!t.isMember(newOwner)) {
            throw new TeamServiceException(TeamError.TARGET_NOT_MEMBER, "team_target_not_member");
        }

        t.setOwner(newOwner);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        markDirty();
        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_owner_transferred_broadcast",
                "{owner}", nameOf(newOwner),
                "{team}", Msg.color(t.getName())
        ));

        // ✅ Redis: owner transferred
        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.OWNER_TRANSFERRED,
                t.getId(),
                t.getName(),
                owner,
                safeName(nameOf(owner)),
                newOwner,
                safeName(nameOf(newOwner)),
                System.currentTimeMillis()
        ));
    }

    @Override
    public void renameTeam(UUID owner, String newName) {
        if (owner == null) {
            throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");
        }

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) {
            throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");
        }

        String cleaned = normalizeTeamNameOrThrow(newName);

        if (normalizeForCompare(t.getName()).equals(normalizeForCompare(cleaned))) {
            return;
        }

        if (teamNameTakenByOtherTeam(cleaned, t.getId())) {
            throw new TeamServiceException(TeamError.TEAM_NAME_TAKEN, "team_name_taken");
        }

        String old = t.getName();
        t.setName(cleaned);

        markDirty();
        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_renamed_broadcast",
                "{team}", Msg.color(t.getName()),
                "{by}", nameOf(owner),
                "{old}", Msg.color(old)
        ));

        // ✅ Redis: team renamed
        // Convention: teamName=newName, targetName=oldName
        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.TEAM_RENAMED,
                t.getId(),
                t.getName(),
                owner,
                safeName(nameOf(owner)),
                null,
                old,
                System.currentTimeMillis()
        ));
    }

    // =========================
    // Team chat (no persistence)
    // =========================

    @Override
    public boolean isTeamChatEnabled(UUID player) {
        return player != null && teamChatToggled.contains(player);
    }

    @Override
    public void setTeamChatEnabled(UUID player, boolean enabled) {
        if (player == null) return;
        if (enabled) teamChatToggled.add(player);
        else teamChatToggled.remove(player);
    }

    @Override
    public boolean toggleTeamChat(UUID player) {
        if (player == null) return false;
        if (teamChatToggled.remove(player)) return false;
        teamChatToggled.add(player);
        return true;
    }

    @Override
    public void sendTeamChat(Player sender, String message) {
        if (sender == null) return;

        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            plugin.msg().send(sender, "teamchat_disabled");
            return;
        }

        Team team = getTeamByPlayer(sender.getUniqueId()).orElse(null);
        if (team == null) {
            teamChatToggled.remove(sender.getUniqueId());
            plugin.msg().send(sender, "team_not_in_team");
            return;
        }

        String msg = (message == null ? "" : message.trim());
        if (msg.isEmpty()) return;

        String fmt = plugin.getConfig().getString(
                "chat.format",
                "&8&l(&c&l{team}&8&l) &f{player} &8&l> &c{message}"
        );
        if (fmt == null || fmt.isBlank()) {
            fmt = "&8&l(&c&l{team}&8&l) &f{player} &8&l> &c{message}";
        }

        String coloredMsg = Msg.color(msg);

        String out = Msg.color(
                fmt.replace("{player}", sender.getName())
                        .replace("{team}", Msg.color(team.getName()))
                        .replace("{message}", coloredMsg)
        );

        // ✅ Debug tagging (helps prove where duplicates originate)
        boolean debug = plugin.getConfig().getBoolean("chat.debug", false);
        if (debug) out = out + Msg.color(" &8[&aLOCAL&8]");

        // 1) Local broadcast
        broadcastToTeam(team, out);

        // 2) Local spy broadcast
        broadcastToSpy(team, sender.getUniqueId(), sender.getName(), coloredMsg);

        // 3) Cross-server publish (ONLY if network is enabled/running)
        if (plugin.isTeamChatNetworkEnabled()) {
            plugin.publishTeamChat(new TeamChatPacket(
                    plugin.networkServerName(),
                    team.getId(),
                    sender.getUniqueId(),
                    sender.getName(),
                    out,
                    System.currentTimeMillis()
            ));
        }
    }

    // =========================
    // Spy (no persistence)
    // =========================

    @Override
    public boolean toggleSpy(UUID spyPlayer, UUID teamId) {
        if (spyPlayer == null || teamId == null) return false;

        Team t = teams.get(teamId);
        if (t == null) return false;

        Set<UUID> set = spyTargets.computeIfAbsent(spyPlayer, __ -> ConcurrentHashMap.newKeySet());
        if (set.remove(teamId)) {
            if (set.isEmpty()) spyTargets.remove(spyPlayer);
            return false;
        }

        set.add(teamId);
        return true;
    }

    @Override
    public void clearSpy(UUID spyPlayer) {
        if (spyPlayer == null) return;
        spyTargets.remove(spyPlayer);
    }

    @Override
    public Collection<Team> getSpiedTeams(UUID spyPlayer) {
        if (spyPlayer == null) return List.of();
        Set<UUID> set = spyTargets.get(spyPlayer);
        if (set == null || set.isEmpty()) return List.of();

        List<Team> out = new ArrayList<>();
        for (UUID id : set) {
            Team t = teams.get(id);
            if (t != null) out.add(t);
        }

        out.sort(Comparator.comparing(a -> normalizeForCompare(a.getName())));
        return out;
    }

    private void broadcastToSpy(Team team, UUID senderUuid, String senderName, String coloredMessage) {
        if (team == null) return;
        if (!plugin.getConfig().getBoolean("chat.spy.enabled", true)) return;

        String spyFmt = plugin.getConfig().getString(
                "chat.spy.format",
                "&8[&cTEAM SPY&8] &8(&c{team}&8) &f{player}&8: &7{message}"
        );
        if (spyFmt == null || spyFmt.isBlank()) {
            spyFmt = "&8[&cTEAM SPY&8] &8(&c{team}&8) &f{player}&8: &7{message}";
        }

        String spyOut = Msg.color(
                spyFmt.replace("{team}", Msg.color(team.getName()))
                        .replace("{player}", (senderName == null ? "unknown" : senderName))
                        .replace("{message}", (coloredMessage == null ? "" : coloredMessage))
        );

        UUID teamId = team.getId();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null) continue;
            if (!p.hasPermission(SPY_PERMISSION)) continue;

            UUID spyUuid = p.getUniqueId();
            if (spyUuid == null) continue;

            if (areTeammates(spyUuid, senderUuid)) continue;

            Set<UUID> watching = spyTargets.get(spyUuid);
            if (watching == null || watching.isEmpty()) continue;
            if (!watching.contains(teamId)) continue;

            p.sendMessage(spyOut);
        }
    }

    private void removeTeamFromAllSpyTargets(UUID teamId) {
        if (teamId == null) return;

        for (Map.Entry<UUID, Set<UUID>> e : spyTargets.entrySet()) {
            Set<UUID> set = e.getValue();
            if (set == null) continue;
            set.remove(teamId);
        }

        spyTargets.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
    }

    // =========================
    // Admin
    // =========================

    @Override
    public void adminDisbandTeam(UUID teamId) {
        if (teamId == null) return;
        Team t = teams.get(teamId);
        if (t == null) return;

        broadcastToTeam(t, plugin.msg().format(
                "team_team_disbanded",
                "{team}", Msg.color(t.getName())
        ));

        internalDisbandTeam(t);

        markDirty();
        safeSave();

        if (isSqlMode()) {
            try { sqlInvites().deleteAllForTeam(teamId); } catch (Exception ignored) {}
        }

        // FIX: keep constructor arg-shape consistent (targetUuid + targetName present)
        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.TEAM_DISBANDED,
                teamId,
                t.getName(),
                t.getOwner() == null ? UUID.randomUUID() : t.getOwner(),
                safeName(nameOf(t.getOwner())),
                null,
                "",
                System.currentTimeMillis()
        ));
    }

    @Override
    public void adminSetOwner(UUID teamId, UUID newOwner) {
        if (teamId == null || newOwner == null) return;

        Team t = teams.get(teamId);
        if (t == null) return;

        UUID other = playerToTeam.get(newOwner);
        if (other != null && !other.equals(teamId)) {
            adminKickPlayer(newOwner);
        }

        UUID oldOwner = t.getOwner();
        t.setOwner(newOwner);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.put(newOwner, teamId);

        markDirty();
        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_owner_transferred_broadcast",
                "{owner}", nameOf(newOwner),
                "{team}", Msg.color(t.getName())
        ));

        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.OWNER_TRANSFERRED,
                t.getId(),
                t.getName(),
                (oldOwner == null ? newOwner : oldOwner),
                safeName(nameOf(oldOwner)),
                newOwner,
                safeName(nameOf(newOwner)),
                System.currentTimeMillis()
        ));
    }

    @Override
    public void adminKickPlayer(UUID player) {
        if (player == null) return;

        Team t = getTeamByPlayer(player).orElse(null);
        if (t == null) return;

        if (player.equals(t.getOwner())) {
            adminDisbandTeam(t.getId());
            return;
        }

        t.getMembers().remove(player);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.remove(player);
        teamChatToggled.remove(player);
        invites.clearTarget(player);

        markDirty();
        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_member_kicked_broadcast",
                "{player}", nameOf(player),
                "{team}", Msg.color(t.getName())
        ));

        publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                TeamEventPacket.Type.MEMBER_KICKED,
                t.getId(),
                t.getName(),
                t.getOwner() == null ? UUID.randomUUID() : t.getOwner(),
                safeName(nameOf(t.getOwner())),
                player,
                safeName(nameOf(player)),
                System.currentTimeMillis()
        ));
    }

    // =========================
    // Internals / helpers
    // =========================

    private void internalDisbandTeam(Team t) {
        if (t == null) return;

        Set<UUID> members = new HashSet<>(t.getMembers());
        for (UUID m : members) {
            if (m == null) continue;
            playerToTeam.remove(m);
            teamChatToggled.remove(m);
            invites.clearTarget(m);
        }

        if (t.getOwner() != null) {
            inviteCooldownUntil.remove(t.getOwner());
        }

        teams.remove(t.getId());

        invites.clearTeam(t.getId());
        removeTeamFromAllSpyTargets(t.getId());

        if (plugin.teamHomes() != null) {
            plugin.teamHomes().clearTeam(t.getId());
        }
    }

    private void broadcastToTeam(Team team, String message) {
        if (team == null) return;
        if (message == null || message.isBlank()) return;

        Set<UUID> members = new HashSet<>(team.getMembers());
        for (UUID uuid : members) {
            if (uuid == null) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";

        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();

        var off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();

        return uuid.toString().substring(0, 8);
    }

    private void safeSave() {
        if (!consumeDirty()) return;

        try {
            // TeamStorage expects a TeamService; this class implements TeamService
            storage.saveAll(this);
        } catch (Exception e) {
            dirty.set(true);
            plugin.getLogger().severe("Failed to save teams: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void publishTeamEvent(TeamEventPacket pkt) {
        if (pkt == null) return;
        plugin.publishTeamEvent(pkt);
    }

    private void ensureOwnerInMembers(Team t) {
        if (t == null) return;
        UUID owner = t.getOwner();
        if (owner == null) return;

        if (!t.getMembers().contains(owner)) {
            t.getMembers().add(owner);
        }
    }

    private void dedupeMembers(Team t) {
        if (t == null) return;

        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        for (UUID u : t.getMembers()) {
            if (u != null) set.add(u);
        }

        t.getMembers().clear();
        t.getMembers().addAll(set);
    }

    private int uniqueMemberCount(Team t) {
        if (t == null) return 0;

        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        for (UUID u : t.getMembers()) {
            if (u != null) set.add(u);
        }
        UUID owner = t.getOwner();
        if (owner != null) set.add(owner);

        return set.size();
    }

    private String normalizeTeamNameOrThrow(String name) {
        if (name == null) {
            throw new TeamServiceException(TeamError.INVALID_TEAM_NAME, "team_invalid_name");
        }
        String cleaned = name.trim().replaceAll("\\s{2,}", " ");
        if (cleaned.isBlank()) {
            throw new TeamServiceException(TeamError.INVALID_TEAM_NAME, "team_invalid_name");
        }
        return cleaned;
    }

    private boolean teamNameTaken(String cleanedName) {
        String norm = normalizeForCompare(cleanedName);
        for (Team t : teams.values()) {
            if (t == null) continue;
            String n = t.getName();
            if (n == null) continue;

            if (normalizeForCompare(n).equals(norm)) return true;
        }
        return false;
    }

    private boolean teamNameTakenByOtherTeam(String cleanedName, UUID ourTeamId) {
        String norm = normalizeForCompare(cleanedName);
        for (Team t : teams.values()) {
            if (t == null) continue;

            UUID id = t.getId();
            String n = t.getName();
            if (id == null || n == null) continue;

            if (ourTeamId != null && ourTeamId.equals(id)) continue;
            if (normalizeForCompare(n).equals(norm)) return true;
        }
        return false;
    }

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    private int getTeamMaxMembers(Team team) {
        int def = Math.max(1, plugin.getConfig().getInt("teams.max_members_default", 4));
        if (team == null) return def;

        UUID owner = team.getOwner();
        if (owner == null) return def;

        Player ownerOnline = Bukkit.getPlayer(owner);
        if (ownerOnline == null) return def;

        int best = def;

        for (PermissionAttachmentInfo pai : ownerOnline.getEffectivePermissions()) {
            if (pai == null || !pai.getValue()) continue;

            String perm = pai.getPermission();
            if (perm == null || !perm.startsWith(MAX_PERM_PREFIX)) continue;

            String num = perm.substring(MAX_PERM_PREFIX.length()).trim();
            if (num.isEmpty()) continue;

            try {
                int n = Integer.parseInt(num);
                if (n >= 1 && n <= 200 && n > best) {
                    best = n;
                }
            } catch (NumberFormatException ignored) {}
        }

        return best;
    }
}
