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
        teams.put(t.getId(), t);
        for (UUID m : t.getMembers()) {
            playerToTeam.put(m, t.getId());
        }
    }

    @Override
    public Optional<Team> getTeamByPlayer(UUID player) {
        UUID id = playerToTeam.get(player);
        return id == null ? Optional.empty() : Optional.ofNullable(teams.get(id));
    }

    @Override
    public Optional<Team> getTeamById(UUID teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    @Override
    public Team createTeam(UUID owner, String name) {
        if (playerToTeam.containsKey(owner)) {
            throw new IllegalStateException("Already in a team");
        }
        UUID id = UUID.randomUUID();
        Team t = new Team(id, name, owner);
        teams.put(id, t);
        playerToTeam.put(owner, id);

        storage.saveAll(this);
        return t;
    }

    @Override
    public void disbandTeam(UUID owner) {
        Team t = getTeamByPlayer(owner).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (!t.getOwner().equals(owner)) throw new IllegalStateException("Not owner");

        broadcastToTeam(t, plugin.msg().format(
                "team_team_disbanded",
                "{team}", Msg.color(t.getName())
        ));

        for (UUID m : new HashSet<>(t.getMembers())) {
            playerToTeam.remove(m);
            teamChatToggled.remove(m);
            inviteCooldownUntil.remove(m);
        }

        teams.remove(t.getId());

        // remove invites that point to this team
        invites.values().forEach(map -> map.remove(t.getId()));
        invites.entrySet().removeIf(e -> e.getValue().isEmpty());

        storage.saveAll(this);
    }

    @Override
    public void leaveTeam(UUID player) {
        Team t = getTeamByPlayer(player).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (t.getOwner().equals(player)) throw new IllegalStateException("Owner must disband or transfer later");

        t.getMembers().remove(player);
        playerToTeam.remove(player);
        teamChatToggled.remove(player);
        inviteCooldownUntil.remove(player);

        storage.saveAll(this);

        String name = nameOf(player);
        broadcastToTeam(t, plugin.msg().format(
                "team_member_left",
                "{player}", name,
                "{team}", Msg.color(t.getName())
        ));
    }

    @Override
    public void invite(UUID inviter, UUID invitee) {
        Team t = getTeamByPlayer(inviter).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (!t.getOwner().equals(inviter)) throw new IllegalStateException("Only owner can invite (v1)");

        if (t.isMember(invitee)) throw new IllegalStateException("Already a member");
        if (playerToTeam.containsKey(invitee)) throw new IllegalStateException("Invitee already in a team");

        int cdSeconds = plugin.getConfig().getInt("invites.cooldown_seconds", 10);
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

        int expiry = plugin.getConfig().getInt("invites.expiry_seconds", 300);
        long expiresAt = System.currentTimeMillis() + (expiry * 1000L);

        invites.computeIfAbsent(invitee, k -> new ConcurrentHashMap<>())
                .put(t.getId(), new TeamInvite(t.getId(), inviter, expiresAt));
    }

    @Override
    public Optional<TeamInvite> acceptInvite(UUID invitee, Optional<UUID> teamId) {
        cleanupExpired(invitee);

        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null || map.isEmpty()) return Optional.empty();

        TeamInvite inv;
        if (teamId.isPresent()) {
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

        int max = plugin.getConfig().getInt("teams.max_members_default", 4);
        if (t.getMembers().size() >= max) {
            throw new IllegalStateException("TEAM_FULL");
        }

        t.getMembers().add(invitee);
        playerToTeam.put(invitee, t.getId());

        map.remove(inv.teamId());
        if (map.isEmpty()) invites.remove(invitee);

        storage.saveAll(this);

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
        cleanupExpired(invitee);

        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null || map.isEmpty()) return false;

        if (teamId.isPresent()) {
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
        cleanupExpired(invitee);
        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null) return List.of();
        return List.copyOf(map.values());
    }

    @Override
    public boolean areTeammates(UUID a, UUID b) {
        UUID ta = playerToTeam.get(a);
        UUID tb = playerToTeam.get(b);
        return ta != null && ta.equals(tb);
    }

    @Override
    public boolean isTeamChatEnabled(UUID player) {
        return teamChatToggled.contains(player);
    }

    @Override
    public void setTeamChatEnabled(UUID player, boolean enabled) {
        if (enabled) teamChatToggled.add(player);
        else teamChatToggled.remove(player);
    }

    @Override
    public boolean toggleTeamChat(UUID player) {
        if (teamChatToggled.contains(player)) {
            teamChatToggled.remove(player);
            return false;
        }
        teamChatToggled.add(player);
        return true;
    }

    @Override
    public void sendTeamChat(Player sender, String message) {
        Team team = getTeamByPlayer(sender.getUniqueId()).orElse(null);
        if (team == null) {
            teamChatToggled.remove(sender.getUniqueId());
            sender.sendMessage(plugin.msg().prefix() + "You are not in a team.");
            return;
        }

        String fmt = plugin.getConfig().getString(
                "chat.format",
                "&8[&bTEAM&8] &f{player}&7: &b{message}"
        );

        String out = Msg.color(
                fmt.replace("{player}", sender.getName())
                        .replace("{team}", team.getName())
                        .replace("{message}", message)
        );

        for (UUID uuid : team.getMembers()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) member.sendMessage(out);
        }
    }

    public Collection<Team> allTeams() {
        return teams.values();
    }

    private void cleanupExpired(UUID invitee) {
        Map<UUID, TeamInvite> map = invites.get(invitee);
        if (map == null || map.isEmpty()) return;

        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue().expired(now));
        if (map.isEmpty()) invites.remove(invitee);
    }

    private void broadcastToTeam(Team team, String message) {
        for (UUID uuid : new HashSet<>(team.getMembers())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    public String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();

        var off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();

        return uuid.toString().substring(0, 8);
    }
}
