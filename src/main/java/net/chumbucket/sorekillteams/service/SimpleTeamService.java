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
import net.chumbucket.sorekillteams.storage.TeamStorage;
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
    private final TeamInvites invites;

    private final Map<UUID, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();

    private final Set<UUID> teamChatToggled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> inviteCooldownUntil = new ConcurrentHashMap<>();

    private final Map<UUID, Set<UUID>> spyTargets = new ConcurrentHashMap<>();

    // ✅ Only write to SQL when THIS backend actually changed something
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public SimpleTeamService(SorekillTeamsPlugin plugin, TeamStorage storage) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.invites = Objects.requireNonNull(plugin.invites(), "invites");
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

    /**
     * ✅ FIX: actually remove cached membership mapping.
     * (Your old version only cleared toggles/invites, leaving playerToTeam stale.)
     */
    public void clearCachedMembership(UUID playerUuid) {
        if (playerUuid == null) return;

        playerToTeam.remove(playerUuid);
        teamChatToggled.remove(playerUuid);
        invites.clearTarget(playerUuid);
    }

    /**
     * Remove a team from local cache (used when SQL says the team no longer exists).
     * Does NOT mark dirty (because this is cache reconciliation, not a local “change”).
     */
    public void evictCachedTeam(UUID teamId) {
        if (teamId == null) return;

        Team removed = teams.remove(teamId);
        if (removed != null) {
            for (UUID m : new HashSet<>(removed.getMembers())) {
                if (m != null && teamId.equals(playerToTeam.get(m))) {
                    playerToTeam.remove(m);
                    teamChatToggled.remove(m);
                }
            }
        }

        inviteCooldownUntil.remove(teamId);
        invites.clearTeam(teamId);
        removeTeamFromAllSpyTargets(teamId);
    }

    /**
     * Replace the entire teams snapshot from SQL.
     * Keeps runtime-only state as much as possible, but evicts teams that no longer exist.
     * Does NOT mark dirty.
     */
    public void replaceTeamsSnapshot(Collection<Team> loadedTeams) {
        // rebuild new maps
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

        // swap (thread-safe enough for our usage; all callers apply on main thread)
        teams.clear();
        teams.putAll(newTeams);

        playerToTeam.clear();
        playerToTeam.putAll(newPlayerToTeam);

        // prune runtime state that no longer makes sense
        teamChatToggled.removeIf(u -> !playerToTeam.containsKey(u));
        spyTargets.entrySet().removeIf(e -> {
            Set<UUID> watching = e.getValue();
            if (watching == null) return true;
            watching.removeIf(id -> !teams.containsKey(id));
            return watching.isEmpty();
        });

        // IMPORTANT: snapshot loads must not mark dirty
        dirty.set(false);
    }

    // =========================
    // Storage helpers
    // =========================

    public void putLoadedTeam(Team t) {
        if (t == null) return;

        ensureOwnerInMembers(t);
        dedupeMembers(t);

        teams.put(t.getId(), t);
        for (UUID m : t.getMembers()) {
            if (m != null) playerToTeam.put(m, t.getId());
        }
        // loading/backfill should NOT mark dirty
    }

    public void putAllTeams(Collection<Team> loadedTeams) {
        // startup load can behave like replace
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
            if (refreshed) return;
        }

        boolean created = invites.create(inv, now);
        if (!created) {
            throw new TeamServiceException(TeamError.INVITE_ALREADY_PENDING, "team_invite_already_pending");
        }
    }

    @Override
    public Optional<TeamInvite> acceptInvite(UUID invitee, Optional<UUID> teamId) {
        if (invitee == null) return Optional.empty();

        long now = System.currentTimeMillis();
        List<TeamInvite> active = invites.listActive(invitee, now);
        if (active.isEmpty()) return Optional.empty();

        if (playerToTeam.containsKey(invitee)) {
            invites.clearTarget(invitee);
            throw new TeamServiceException(TeamError.ALREADY_IN_TEAM, "team_already_in_team");
        }

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

        return Optional.of(inv);
    }

    @Override
    public boolean denyInvite(UUID invitee, Optional<UUID> teamId) {
        if (invitee == null) return false;

        long now = System.currentTimeMillis();
        List<TeamInvite> active = invites.listActive(invitee, now);
        if (active.isEmpty()) {
            invites.clearTarget(invitee);
            return false;
        }

        if (teamId != null && teamId.isPresent()) {
            return invites.remove(invitee, teamId.get());
        }

        if (active.size() > 1) {
            throw new TeamServiceException(TeamError.MULTIPLE_INVITES, "team_multiple_invites_hint");
        }

        return invites.remove(invitee, active.get(0).getTeamId());
    }

    @Override
    public Collection<TeamInvite> getInvites(UUID invitee) {
        if (invitee == null) return List.of();
        long now = System.currentTimeMillis();
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

        String out = Msg.color(
                fmt.replace("{player}", sender.getName())
                        .replace("{team}", Msg.color(team.getName()))
                        .replace("{message}", Msg.color(msg))
        );

        broadcastToTeam(team, out);
        broadcastToSpy(team, sender.getUniqueId(), sender.getName(), Msg.color(msg));
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
        }

        inviteCooldownUntil.remove(t.getOwner());
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
            storage.saveAll(this);
        } catch (Exception e) {
            dirty.set(true);
            plugin.getLogger().severe("Failed to save teams: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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
