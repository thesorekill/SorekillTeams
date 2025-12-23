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

import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamInvite;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TeamService {

    Optional<Team> getTeamByPlayer(UUID player);
    Optional<Team> getTeamById(UUID teamId);
    Optional<Team> getTeamByName(String teamName);

    Team createTeam(UUID owner, String name);

    void disbandTeam(UUID owner);
    void leaveTeam(UUID player);

    void invite(UUID inviter, UUID invitee);

    Optional<TeamInvite> acceptInvite(UUID invitee, Optional<UUID> teamId);

    boolean denyInvite(UUID invitee, Optional<UUID> teamId);

    Collection<TeamInvite> getInvites(UUID invitee);

    boolean areTeammates(UUID a, UUID b);

    void kickMember(UUID owner, UUID member);

    void transferOwnership(UUID owner, UUID newOwner);

    void renameTeam(UUID owner, String newName);

    // Team Chat (A)
    boolean isTeamChatEnabled(UUID player);
    void setTeamChatEnabled(UUID player, boolean enabled);
    boolean toggleTeamChat(UUID player);
    void sendTeamChat(Player sender, String message);

    // Spy (A)
    boolean toggleSpy(UUID spyPlayer, UUID teamId);
    void clearSpy(UUID spyPlayer);
    Collection<Team> getSpiedTeams(UUID spyPlayer);

    // =========================
    // Admin (D)
    // =========================

    /** Force-disband a team, regardless of owner. */
    void adminDisbandTeam(UUID teamId);

    /** Force-set the owner of a team, ensuring new owner is a member. */
    void adminSetOwner(UUID teamId, UUID newOwner);

    /** Force-kick a player from their team. If they are owner, disbands the team. */
    void adminKickPlayer(UUID player);
}
