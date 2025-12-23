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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleTeamService implements TeamService {

    private final SorekillTeamsPlugin plugin;
    private final TeamStorage storage;
    private final TeamInvites invites; // 1.0.3 invite store

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

        inviteCooldownUntil.remove(owner);

        teams.remove(t.getId());

        // remove invites that point to this team (1.0.3 store)
        invites.clearTeam(t.getId());

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

        // Optional: clear any invites for this player (reduces confusion)
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
        if (inviter == null || invitee == null) throw new IllegalStateException("Invalid player");
        if (inviter.equals(invitee)) throw new IllegalStateException("You cannot invite yourself");

        Team t = getTeamByPlayer(inviter).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (!t.getOwner().equals(inviter)) throw new IllegalStateException("Only owner can invite (v1)");

        if (t.isMember(invitee)) throw new IllegalStateException("Already a member");
        if (playerToTeam.containsKey(invitee)) throw new IllegalStateException("Invitee already in a team");

        // Cooldown
        int cdSeconds = Math.max(0, plugin.getConfig().getInt("invites.cooldown_seconds", 10));
        long now = System.currentTimeMillis();

        if (cdSeconds > 0) {
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

        // Expiry
        int expirySeconds = Math.max(1, plugin.getConfig().getInt("invites.expiry_seconds", 300));
        long expiresAt = now + (expirySeconds * 1000L);

        // Duplicate prevention + store
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
            throw new IllegalStateException("Already invited");
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
            throw new IllegalStateException("Invitee already in a team");
        }

        TeamInvite inv;

        if (teamId != null && teamId.isPresent()) {
            UUID id = teamId.get();
            inv = invites.get(invitee, id, now).orElse(null);
            if (inv == null) return Optional.empty();
        } else {
            if (active.size() > 1) throw new IllegalStateException("MULTIPLE_INVITES");
            inv = active.get(0);
        }

        if (inv.isExpired(now)) {
            invites.remove(invitee, inv.getTeamId());
            throw new IllegalStateException("INVITE_EXPIRED");
        }

        Team t = teams.get(inv.getTeamId());
        if (t == null) {
            invites.remove(invitee, inv.getTeamId());
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
            if (active.size() > 1) throw new IllegalStateException("MULTIPLE_INVITES");
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
            // was hardcoded; now messages.yml-driven
            plugin.msg().send(sender, "team_not_in_team");
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
        if (name == null) throw new IllegalStateException("Invalid team name");
        String cleaned = name.trim().replaceAll("\\s{2,}", " ");
        if (cleaned.isBlank()) throw new IllegalStateException("Invalid team name");
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