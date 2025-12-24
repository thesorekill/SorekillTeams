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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Team {

    private final UUID id;
    private String name;
    private UUID owner;

    // LinkedHashSet for deterministic order
    private final Set<UUID> members = new LinkedHashSet<>();

    // per-team friendly fire
    private boolean friendlyFireEnabled = false;

    // created timestamp (epoch ms)
    private final long createdAtMs;

    public Team(UUID id, String name, UUID owner) {
        this(id, name, owner, System.currentTimeMillis());
    }

    public Team(UUID id, String name, UUID owner, long createdAtMs) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.createdAtMs = createdAtMs > 0 ? createdAtMs : System.currentTimeMillis();

        setName(name);
        this.members.add(owner);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * Keeps it safe even if null/blank is passed.
     * Strict validation belongs in TeamNameValidator / service layer.
     */
    public void setName(String name) {
        String cleaned = (name == null) ? "" : name.trim().replaceAll("\\s{2,}", " ");
        this.name = cleaned.isBlank() ? "Team" : cleaned;
    }

    public UUID getOwner() {
        return owner;
    }

    /**
     * Transfers ownership. Guarantees the new owner is a member.
     */
    public void setOwner(UUID owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.members.add(owner);
    }

    /**
     * Mutable internal set (service/storage rely on this).
     */
    public Set<UUID> getMembers() {
        return members;
    }

    /**
     * Read-only view for display logic.
     */
    public Set<UUID> getMembersView() {
        return Collections.unmodifiableSet(members);
    }

    public boolean isMember(UUID uuid) {
        return uuid != null && members.contains(uuid);
    }

    public boolean isFriendlyFireEnabled() {
        return friendlyFireEnabled;
    }

    public void setFriendlyFireEnabled(boolean friendlyFireEnabled) {
        this.friendlyFireEnabled = friendlyFireEnabled;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    /** Convenience helper if you want it. */
    public String renameTo(String newName) {
        String old = this.name;
        setName(newName);
        return old;
    }
}