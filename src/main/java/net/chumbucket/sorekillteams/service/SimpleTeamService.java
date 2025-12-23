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

public final class SimpleTeamService implements TeamService {

    private static final String MAX_PERM_PREFIX = "sorekillteams.max.";

    private final SorekillTeamsPlugin plugin;
    private final TeamStorage storage;
    private final TeamInvites invites; // invite store

    private final Map<UUID, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();

    // Players who have /tc toggled ON
    private final Set<UUID> teamChatToggled = ConcurrentHashMap.newKeySet();

    // Invite cooldown: inviter -> next allowed timestamp (ms)
    private final Map<UUID, Long> inviteCooldownUntil = new ConcurrentHashMap<>();

    public SimpleTeamService(SorekillTeamsPlugin plugin, TeamStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.invites = plugin.invites();
    }

    // called by storage on load
    public void putLoadedTeam(Team t) {
        if (t == null) return;

        // Enforce invariants on load too
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        teams.put(t.getId(), t);
        for (UUID m : t.getMembers()) {
            if (m != null) playerToTeam.put(m, t.getId());
        }
    }

    @Override
    public Optional<Team> getTeamByPlayer(UUID player) {
        if (player == null) return Optional.empty();
        UUID id = playerToTeam.get(player);
        return id == null ? Optional.empty() : Optional.ofNullable(teams.get(id));
    }

    @Override
    public Optional<Team> getTeamById(UUID teamId) {
        if (teamId == null) return Optional.empty();
        return Optional.ofNullable(teams.get(teamId));
    }

    @Override
    public Team createTeam(UUID owner, String name) {
        if (owner == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");
        if (playerToTeam.containsKey(owner)) throw new TeamServiceException(TeamError.ALREADY_IN_TEAM, "team_already_in_team");

        final String cleanName = normalizeTeamNameOrThrow(name);

        // prevent duplicates by normalized name
        if (teamNameTaken(cleanName)) {
            throw new TeamServiceException(TeamError.TEAM_NAME_TAKEN, "team_name_taken");
        }

        UUID id = UUID.randomUUID();

        // Team tracks createdAtMs internally (Team constructor sets it)
        Team t = new Team(id, cleanName, owner);

        ensureOwnerInMembers(t);
        dedupeMembers(t);

        teams.put(id, t);
        playerToTeam.put(owner, id);

        safeSave();
        return t;
    }

    @Override
    public void disbandTeam(UUID owner) {
        if (owner == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");

        broadcastToTeam(t, plugin.msg().format(
                "team_team_disbanded",
                "{team}", Msg.color(t.getName())
        ));

        // Snapshot members to avoid concurrent modification
        Set<UUID> members = new HashSet<>(t.getMembers());
        for (UUID m : members) {
            if (m == null) continue;
            playerToTeam.remove(m);
            teamChatToggled.remove(m);
        }

        inviteCooldownUntil.remove(owner);

        teams.remove(t.getId());

        // remove invites that point to this team
        invites.clearTeam(t.getId());

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

        safeSave();

        String name = nameOf(player);
        broadcastToTeam(t, plugin.msg().format(
                "team_member_left",
                "{player}", name,
                "{team}", Msg.color(t.getName())
        ));
    }

    @Override
    public void invite(UUID inviter, UUID invitee) {
        if (inviter == null || invitee == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");
        if (inviter.equals(invitee)) throw new TeamServiceException(TeamError.INVITE_SELF, "team_invite_self");

        Team t = getTeamByPlayer(inviter).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(inviter)) {
            throw new TeamServiceException(TeamError.ONLY_OWNER_CAN_INVITE, "team_not_owner");
        }

        // ✅ 1.0.7: pre-check capacity (permission-based max for owner)
        int max = getTeamMaxMembers(t);
        if (uniqueMemberCount(t) >= max) {
            throw new TeamServiceException(TeamError.TEAM_FULL, "team_team_full");
        }

        if (t.isMember(invitee)) throw new TeamServiceException(TeamError.ALREADY_MEMBER, "team_already_member");
        if (playerToTeam.containsKey(invitee)) throw new TeamServiceException(TeamError.INVITEE_IN_TEAM, "team_invitee_in_team");

        // Cooldown
        int cdSeconds = Math.max(0, plugin.getConfig().getInt("invites.cooldown_seconds", 10));
        long now = System.currentTimeMillis();

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

        // Expiry
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

        TeamInvite inv;

        if (teamId != null && teamId.isPresent()) {
            UUID id = teamId.get();
            inv = invites.get(invitee, id, now).orElse(null);
            if (inv == null) return Optional.empty();
        } else {
            if (active.size() > 1) throw new TeamServiceException(TeamError.MULTIPLE_INVITES, "team_multiple_invites_hint");
            inv = active.get(0);
        }

        if (inv.isExpired(now)) {
            invites.remove(invitee, inv.getTeamId());
            throw new TeamServiceException(TeamError.INVITE_EXPIRED, "team_invite_expired");
        }

        Team t = teams.get(inv.getTeamId());
        if (t == null) {
            invites.remove(invitee, inv.getTeamId());
            return Optional.empty();
        }

        ensureOwnerInMembers(t);
        dedupeMembers(t);

        // ✅ 1.0.7: permission-based max for owner
        int max = getTeamMaxMembers(t);
        int currentSize = uniqueMemberCount(t);

        if (currentSize >= max) {
            throw new TeamServiceException(TeamError.TEAM_FULL, "team_team_full");
        }

        t.getMembers().add(invitee);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.put(invitee, t.getId());

        invites.remove(invitee, inv.getTeamId());

        safeSave();

        String name = nameOf(invitee);
        broadcastToTeam(t, plugin.msg().format(
                "team_member_joined",
                "{player}", name,
                "{team}", Msg.color(t.getName())
        ));

        return Optional.of(inv);
    }

    @Override
    public boolean denyInvite(UUID invitee, Optional<UUID> teamId) {
        if (invitee == null) return false;

        long now = System.currentTimeMillis();
        List<TeamInvite> active = invites.listActive(invitee, now);
        if (active.isEmpty()) return false;

        if (teamId != null && teamId.isPresent()) {
            return invites.remove(invitee, teamId.get());
        } else {
            if (active.size() > 1) throw new TeamServiceException(TeamError.MULTIPLE_INVITES, "team_multiple_invites_hint");
            return invites.remove(invitee, active.get(0).getTeamId());
        }
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

    // 1.0.6: kick member (owner only)
    @Override
    public void kickMember(UUID owner, UUID member) {
        if (owner == null || member == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");

        if (member.equals(t.getOwner())) throw new TeamServiceException(TeamError.CANNOT_KICK_OWNER, "team_cannot_kick_owner");

        if (!t.isMember(member)) throw new TeamServiceException(TeamError.TARGET_NOT_MEMBER, "team_kick_not_member");

        t.getMembers().remove(member);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.remove(member);
        teamChatToggled.remove(member);

        invites.clearTarget(member);

        safeSave();

        String memberName = nameOf(member);
        broadcastToTeam(t, plugin.msg().format(
                "team_member_kicked_broadcast",
                "{player}", memberName,
                "{team}", Msg.color(t.getName())
        ));
    }

    // 1.0.6: transfer ownership (owner -> member)
    @Override
    public void transferOwnership(UUID owner, UUID newOwner) {
        if (owner == null || newOwner == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");

        if (owner.equals(newOwner)) throw new TeamServiceException(TeamError.TRANSFER_SELF, "team_transfer_self");

        if (!t.isMember(newOwner)) throw new TeamServiceException(TeamError.TARGET_NOT_MEMBER, "team_target_not_member");

        t.setOwner(newOwner);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        safeSave();

        String newOwnerName = nameOf(newOwner);
        broadcastToTeam(t, plugin.msg().format(
                "team_owner_transferred_broadcast",
                "{owner}", newOwnerName,
                "{team}", Msg.color(t.getName())
        ));
    }

    // ✅ 1.0.7: rename team (owner only)
    @Override
    public void renameTeam(UUID owner, String newName) {
        if (owner == null) throw new TeamServiceException(TeamError.INVALID_PLAYER, "invalid_player");

        Team t = getTeamByPlayer(owner).orElseThrow(() ->
                new TeamServiceException(TeamError.NOT_IN_TEAM, "team_not_in_team"));

        if (!t.getOwner().equals(owner)) throw new TeamServiceException(TeamError.NOT_OWNER, "team_not_owner");

        String cleaned = normalizeTeamNameOrThrow(newName);

        // If name is effectively the same, no-op (still "success")
        if (normalizeForCompare(t.getName()).equals(normalizeForCompare(cleaned))) {
            return;
        }

        // Check taken excluding our team
        if (teamNameTakenByOtherTeam(cleaned, t.getId())) {
            throw new TeamServiceException(TeamError.TEAM_NAME_TAKEN, "team_name_taken");
        }

        String old = t.getName();
        t.setName(cleaned);

        safeSave();

        broadcastToTeam(t, plugin.msg().format(
                "team_renamed_broadcast",
                "{old}", Msg.color(old),
                "{team}", Msg.color(t.getName())
        ));
    }

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
        if (teamChatToggled.contains(player)) {
            teamChatToggled.remove(player);
            return false;
        }
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

        if (message == null) message = "";
        message = message.trim();
        if (message.isEmpty()) return;

        String fmt = plugin.getConfig().getString(
                "chat.format",
                "&8&l(&c&l{team}&8&l) &f{player} &8&l> &c{message}"
        );

        if (fmt == null || fmt.isBlank()) {
            fmt = "&8&l(&c&l{team}&8&l) &f{player} &8&l> &c{message}";
        }

        String teamName = Msg.color(team.getName());
        String coloredMsg = Msg.color(message);

        String out = Msg.color(
                fmt.replace("{player}", sender.getName())
                        .replace("{team}", teamName)
                        .replace("{message}", coloredMsg)
        );

        broadcastToTeam(team, out);
    }

    public Collection<Team> allTeams() {
        return teams.values();
    }

    /* ----------------- helpers ----------------- */

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
        try {
            storage.saveAll(this);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save teams: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void ensureOwnerInMembers(Team t) {
        if (t == null) return;
        if (t.getOwner() == null) return;
        if (!t.getMembers().contains(t.getOwner())) {
            t.getMembers().add(t.getOwner());
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
        if (t.getOwner() != null) set.add(t.getOwner());
        return set.size();
    }

    private String normalizeTeamNameOrThrow(String name) {
        if (name == null) throw new TeamServiceException(TeamError.INVALID_TEAM_NAME, "team_invalid_name");
        String cleaned = name.trim().replaceAll("\\s{2,}", " ");
        if (cleaned.isBlank()) throw new TeamServiceException(TeamError.INVALID_TEAM_NAME, "team_invalid_name");
        return cleaned;
    }

    private boolean teamNameTaken(String cleanedName) {
        String norm = normalizeForCompare(cleanedName);
        for (Team t : teams.values()) {
            if (t == null || t.getName() == null) continue;
            if (normalizeForCompare(t.getName()).equals(norm)) return true;
        }
        return false;
    }

    private boolean teamNameTakenByOtherTeam(String cleanedName, UUID ourTeamId) {
        String norm = normalizeForCompare(cleanedName);
        for (Team t : teams.values()) {
            if (t == null || t.getId() == null || t.getName() == null) continue;
            if (ourTeamId != null && ourTeamId.equals(t.getId())) continue;
            if (normalizeForCompare(t.getName()).equals(norm)) return true;
        }
        return false;
    }

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    // ✅ 1.0.7: permission-based max members for team owner
    private int getTeamMaxMembers(Team team) {
        int def = Math.max(1, plugin.getConfig().getInt("teams.max_members_default", 4));
        if (team == null || team.getOwner() == null) return def;

        Player ownerOnline = Bukkit.getPlayer(team.getOwner());
        if (ownerOnline == null) return def; // offline owner -> fallback

        int best = def;

        // Scan effective permissions for sorekillteams.max.N
        for (PermissionAttachmentInfo pai : ownerOnline.getEffectivePermissions()) {
            if (pai == null || !pai.getValue()) continue;
            String perm = pai.getPermission();
            if (perm == null) continue;
            if (!perm.startsWith(MAX_PERM_PREFIX)) continue;

            String num = perm.substring(MAX_PERM_PREFIX.length()).trim();
            if (num.isEmpty()) continue;

            try {
                int n = Integer.parseInt(num);
                if (n >= 1 && n <= 200) { // reasonable cap to avoid accidents
                    if (n > best) best = n;
                }
            } catch (NumberFormatException ignored) {}
        }

        return best;
    }
}