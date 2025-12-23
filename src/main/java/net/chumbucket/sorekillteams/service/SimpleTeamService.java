package net.chumbucket.sorekillteams.service;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamInvite;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleTeamService implements TeamService {

    private final SorekillTeamsPlugin plugin;
    private final TeamStorage storage;

    private final Map<UUID, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();
    private final Map<UUID, TeamInvite> invites = new ConcurrentHashMap<>();

    // Players who have /tc toggled ON
    private final Set<UUID> teamChatToggled = ConcurrentHashMap.newKeySet();

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

        // remove membership mappings + disable team chat for those players
        for (UUID m : new HashSet<>(t.getMembers())) {
            playerToTeam.remove(m);
            teamChatToggled.remove(m);
        }

        teams.remove(t.getId());

        // remove invites pointing to this team
        invites.entrySet().removeIf(e -> e.getValue().teamId().equals(t.getId()));

        storage.saveAll(this);
    }

    @Override
    public void leaveTeam(UUID player) {
        Team t = getTeamByPlayer(player).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (t.getOwner().equals(player)) throw new IllegalStateException("Owner must disband or transfer later");

        t.getMembers().remove(player);
        playerToTeam.remove(player);
        teamChatToggled.remove(player); // leaving team disables team chat
        storage.saveAll(this);
    }

    @Override
    public void invite(UUID inviter, UUID invitee) {
        Team t = getTeamByPlayer(inviter).orElseThrow(() -> new IllegalStateException("Not in a team"));
        if (!t.getOwner().equals(inviter)) throw new IllegalStateException("Only owner can invite (v1)");

        if (t.isMember(invitee)) throw new IllegalStateException("Already a member");
        if (playerToTeam.containsKey(invitee)) throw new IllegalStateException("Invitee already in a team");

        int expiry = plugin.getConfig().getInt("invites.expiry_seconds", 300);
        long expiresAt = System.currentTimeMillis() + (expiry * 1000L);
        invites.put(invitee, new TeamInvite(t.getId(), inviter, expiresAt));
    }

    @Override
    public boolean acceptInvite(UUID invitee) {
        TeamInvite inv = invites.get(invitee);
        if (inv == null) return false;

        long now = System.currentTimeMillis();
        if (inv.expired(now)) {
            invites.remove(invitee);
            return false;
        }

        Team t = teams.get(inv.teamId());
        if (t == null) {
            invites.remove(invitee);
            return false;
        }

        int max = plugin.getConfig().getInt("teams.max_members_default", 4);
        if (t.getMembers().size() >= max) {
            throw new IllegalStateException("Team is full");
        }

        t.getMembers().add(invitee);
        playerToTeam.put(invitee, t.getId());
        invites.remove(invitee);

        storage.saveAll(this);
        return true;
    }

    @Override
    public Optional<TeamInvite> getInvite(UUID invitee) {
        TeamInvite inv = invites.get(invitee);
        if (inv == null) return Optional.empty();

        if (inv.expired(System.currentTimeMillis())) {
            invites.remove(invitee);
            return Optional.empty();
        }
        return Optional.of(inv);
    }

    @Override
    public boolean areTeammates(UUID a, UUID b) {
        UUID ta = playerToTeam.get(a);
        UUID tb = playerToTeam.get(b);
        return ta != null && ta.equals(tb);
    }

    // Team chat toggle mode
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
            // If they somehow toggled while not in a team, auto-disable
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

    // used by storage
    public Collection<Team> allTeams() {
        return teams.values();
    }

    // convenience
    public String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : uuid.toString();
    }
}