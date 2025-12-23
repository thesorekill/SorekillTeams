/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.model;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class Team {
    private final UUID id;
    private String name;
    private UUID owner;
    private final Set<UUID> members = new HashSet<>();

    // ✅ 1.0.5: per-team friendly fire
    private boolean friendlyFireEnabled = false;

    // ✅ 1.0.5: created timestamp (epoch ms)
    private final long createdAtMs;

    public Team(UUID id, String name, UUID owner) {
        this(id, name, owner, System.currentTimeMillis());
    }

    public Team(UUID id, String name, UUID owner, long createdAtMs) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.createdAtMs = createdAtMs > 0 ? createdAtMs : System.currentTimeMillis();
        this.members.add(owner);
    }

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }

    public Set<UUID> getMembers() { return members; }
    public boolean isMember(UUID uuid) { return members.contains(uuid); }

    public boolean isFriendlyFireEnabled() { return friendlyFireEnabled; }
    public void setFriendlyFireEnabled(boolean friendlyFireEnabled) { this.friendlyFireEnabled = friendlyFireEnabled; }

    public long getCreatedAtMs() { return createdAtMs; }
}