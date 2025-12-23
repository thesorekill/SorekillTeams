package net.chumbucket.sorekillteams.service;

import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamInvite;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface TeamService {

    Optional<Team> getTeamByPlayer(UUID player);
    Optional<Team> getTeamById(UUID teamId);

    Team createTeam(UUID owner, String name);

    void disbandTeam(UUID owner);
    void leaveTeam(UUID player);

    void invite(UUID inviter, UUID invitee);
    boolean acceptInvite(UUID invitee);

    Optional<TeamInvite> getInvite(UUID invitee);

    boolean areTeammates(UUID a, UUID b);

    // Team chat toggle mode
    boolean isTeamChatEnabled(UUID player);
    void setTeamChatEnabled(UUID player, boolean enabled);
    boolean toggleTeamChat(UUID player);

    // Send a message to the sender's team chat
    void sendTeamChat(Player sender, String message);
}