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

    // Use LinkedHashSet for deterministic save order + stable /team info display
    private final Set<UUID> members = new LinkedHashSet<>();

    // ✅ 1.0.5: per-team friendly fire
    private boolean friendlyFireEnabled = false;

    // ✅ 1.0.5: created timestamp (epoch ms)
    private final long createdAtMs;

    public Team(UUID id, String name, UUID owner) {
        this(id, name, owner, System.currentTimeMillis());
    }

    public Team(UUID id, String name, UUID owner, long createdAtMs) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.createdAtMs = createdAtMs > 0 ? createdAtMs : System.currentTimeMillis();

        setName(name);        // applies normalization + non-blank fallback
        this.members.add(owner); // owner is always a member
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * Sets the team name. Keeps it safe even if a caller passes null/blank.
     * Actual strict validation should happen in the service (TeamNameValidator).
     */
    public void setName(String name) {
        String cleaned = (name == null) ? "" : name.trim().replaceAll("\\s{2,}", " ");
        this.name = cleaned.isBlank() ? "Team" : cleaned;
    }

    public UUID getOwner() {
        return owner;
    }

    /**
     * Transfers ownership. Also guarantees the new owner is a member.
     */
    public void setOwner(UUID owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.members.add(owner);
    }

    /**
     * Mutable internal set (used by storage/service).
     * If you ever want to lock this down later, switch this to unmodifiable
     * and add addMember/removeMember methods.
     */
    public Set<UUID> getMembers() {
        return members;
    }

    /** Read-only view (nice for commands that only display). */
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

    /** Optional: convenience for rename broadcasts/logs. */
    public String renameTo(String newName) {
        String old = this.name;
        setName(newName);
        return old;
    }
}