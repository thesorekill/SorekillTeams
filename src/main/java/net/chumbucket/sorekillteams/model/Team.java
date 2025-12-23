package net.chumbucket.sorekillteams.model;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class Team {
    private final UUID id;
    private String name;
    private UUID owner;
    private final Set<UUID> members = new HashSet<>();

    public Team(UUID id, String name, UUID owner) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.members.add(owner);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }

    public Set<UUID> getMembers() { return members; }

    public boolean isMember(UUID uuid) { return members.contains(uuid); }
}
