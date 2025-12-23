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
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleTeamService implements TeamService {

    private final SorekillTeamsPlugin plugin;
    private final TeamStorage storage;

    private final Map<UUID, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();

    // invitee -> (teamId -> invite)
    private final Map<UUID, Map<UUID, TeamInvite>> invites = new ConcurrentHashMap<>();

    // Players who have /tc toggled ON
    private final Set<UUID> teamChatToggled = ConcurrentHashMap.newKeySet();

    // Invite cooldown: inviter -> next allowed timestamp (ms)
    private final Map<UUID, Long> inviteCooldownUntil = new ConcurrentHashMap<>();

    public SimpleTeamService(SorekillTeamsPlugin plugin, TeamStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
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
        if (owner == null) throw new IllegalStateException("Invalid player");
        if (playerToTeam.containsKey(owner)) throw new IllegalStateException("Already in a team");

        final String cleanName = normalizeTeamNameOrThrow(name);

        // prevent duplicates by normalized name
        if (teamNameTaken(cleanName)) {
            throw new IllegalStateException("Team name already taken");
        }

        UUID id = UUID.randomUUID();
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
        if (owner == null) throw new IllegalStateException("Invalid player");

        Team t = getTeamByPlayer(owner).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (!t.getOwner().equals(owner)) throw new IllegalStateException("Not owner");

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

        // Cooldown is per-inviter; clear only for the owner (optional but sensible)
        inviteCooldownUntil.remove(owner);

        teams.remove(t.getId());

        // remove invites that point to this team
        invites.values().forEach(map -> map.remove(t.getId()));
        invites.entrySet().removeIf(e -> e.getValue().isEmpty());

        safeSave();
    }

    @Override
    public void leaveTeam(UUID player) {
        if (player == null) throw new IllegalStateException("Invalid player");

        Team t = getTeamByPlayer(player).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (t.getOwner().equals(player)) throw new IllegalStateException("Owner must disband or transfer later");

        t.getMembers().remove(player);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.remove(player);
        teamChatToggled.remove(player);

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
        if (inviter == null || invitee == null) throw new IllegalStateException("Invalid player");
        if (inviter.equals(invitee)) throw new IllegalStateException("You cannot invite yourself");

        Team t = getTeamByPlayer(inviter).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (!t.getOwner().equals(inviter)) throw new IllegalStateException("Only owner can invite (v1)");

        if (t.isMember(invitee)) throw new IllegalStateException("Already a member");
        if (playerToTeam.containsKey(invitee)) throw new IllegalStateException("Invitee already in a team");

        int cdSeconds = Math.max(0, plugin.getConfig().getInt("invites.cooldown_seconds", 10));
        if (cdSeconds > 0) {
            long now = System.currentTimeMillis();
            long until = inviteCooldownUntil.getOrDefault(inviter, 0L);
            if (until > now) {
                long remaining = (until - now + 999) / 1000;
                throw new IllegalStateException(plugin.msg().format(
                        "team_invite_cooldown",
                        "{seconds}", String.valueOf(remaining)
                ));
            }
            inviteCooldownUntil.put(inviter, now + (cdSeconds * 1000L));
        }

        int expiry = Math.max(1, plugin.getConfig().getInt("invites.expiry_seconds", 300));
        long expiresAt = System.currentTimeMillis() + (expiry * 1000L);

        invites.computeIfAbsent(invitee, k -> new ConcurrentHashMap<>())
                .put(t.getId(), new TeamInvite(t.getId(), inviter, expiresAt));
    }

    @Override
    public Optional<TeamInvite> acceptInvite(UUID invitee, Optional<UUID> teamId) {
        if (invitee == null) return Optional.empty();

        cleanupExpired(invitee);

        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null || map.isEmpty()) return Optional.empty();

        // If they joined a team since the invite was sent, block now (race safety)
        if (playerToTeam.containsKey(invitee)) {
            // clear any stale invites for this invitee to reduce confusion
            invites.remove(invitee);
            throw new IllegalStateException("Invitee already in a team");
        }

        TeamInvite inv;
        if (teamId != null && teamId.isPresent()) {
            inv = map.get(teamId.get());
            if (inv == null) return Optional.empty();
        } else {
            if (map.size() > 1) throw new IllegalStateException("MULTIPLE_INVITES");
            inv = map.values().iterator().next();
        }

        long now = System.currentTimeMillis();
        if (inv.expired(now)) {
            map.remove(inv.teamId());
            if (map.isEmpty()) invites.remove(invitee);
            throw new IllegalStateException("INVITE_EXPIRED");
        }

        Team t = teams.get(inv.teamId());
        if (t == null) {
            map.remove(inv.teamId());
            if (map.isEmpty()) invites.remove(invitee);
            return Optional.empty();
        }

        ensureOwnerInMembers(t);
        dedupeMembers(t);

        int max = Math.max(1, plugin.getConfig().getInt("teams.max_members_default", 4));
        int currentSize = uniqueMemberCount(t);

        if (currentSize >= max) {
            throw new IllegalStateException("TEAM_FULL");
        }

        t.getMembers().add(invitee);
        ensureOwnerInMembers(t);
        dedupeMembers(t);

        playerToTeam.put(invitee, t.getId());

        map.remove(inv.teamId());
        if (map.isEmpty()) invites.remove(invitee);

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

        cleanupExpired(invitee);

        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null || map.isEmpty()) return false;

        if (teamId != null && teamId.isPresent()) {
            TeamInvite removed = map.remove(teamId.get());
            if (map.isEmpty()) invites.remove(invitee);
            return removed != null;
        } else {
            if (map.size() > 1) throw new IllegalStateException("MULTIPLE_INVITES");
            UUID only = map.keySet().iterator().next();
            map.remove(only);
            invites.remove(invitee);
            return true;
        }
    }

    @Override
    public Collection<TeamInvite> getInvites(UUID invitee) {
        if (invitee == null) return List.of();
        cleanupExpired(invitee);
        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null) return List.of();
        return List.copyOf(map.values());
    }

    @Override
    public boolean areTeammates(UUID a, UUID b) {
        if (a == null || b == null) return false;
        UUID ta = playerToTeam.get(a);
        UUID tb = playerToTeam.get(b);
        return ta != null && ta.equals(tb);
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

        Team team = getTeamByPlayer(sender.getUniqueId()).orElse(null);
        if (team == null) {
            teamChatToggled.remove(sender.getUniqueId());
            sender.sendMessage(plugin.msg().prefix() + "You are not in a team.");
            return;
        }

        if (message == null) message = "";
        message = message.trim();
        if (message.isEmpty()) return;

        String fmt = plugin.getConfig().getString(
                "chat.format",
                "&8[&bTEAM&8] &f{player}&7: &b{message}"
        );

        if (fmt == null || fmt.isBlank()) {
            fmt = "&8[&bTEAM&8] &f{player}&7: &b{message}";
        }

        String out = Msg.color(
                fmt.replace("{player}", sender.getName())
                        .replace("{team}", team.getName())
                        .replace("{message}", message)
        );

        broadcastToTeam(team, out);
    }

    public Collection<Team> allTeams() {
        return teams.values();
    }

    /* ----------------- helpers ----------------- */

    private void cleanupExpired(UUID invitee) {
        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null || map.isEmpty()) return;

        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue().expired(now));
        if (map.isEmpty()) invites.remove(invitee);
    }

    private void broadcastToTeam(Team team, String message) {
        if (team == null) return;
        if (message == null || message.isBlank()) return;

        // Snapshot to avoid concurrent modification if membership changes mid-send
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
        if (name == null) throw new IllegalStateException("Invalid team name");
        String cleaned = name.trim().replaceAll("\\s{2,}", " ");
        if (cleaned.isBlank()) throw new IllegalStateException("Invalid team name");
        // Keep original casing for display, but cleaned for storage
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

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }
}