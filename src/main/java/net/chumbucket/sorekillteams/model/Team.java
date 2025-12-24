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

    private static final String DEFAULT_NAME = "Team";

    private final UUID id;
    private final long createdAtMs;

    private String name;
    private UUID owner;

    // LinkedHashSet for deterministic order (stable saves / display)
    private final Set<UUID> members = new LinkedHashSet<>();

    // Per-team friendly fire (team setting)
    private boolean friendlyFireEnabled;

    public Team(UUID id, String name, UUID owner) {
        this(id, name, owner, System.currentTimeMillis());
    }

    public Team(UUID id, String name, UUID owner, long createdAtMs) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.createdAtMs = createdAtMs > 0 ? createdAtMs : System.currentTimeMillis();

        setName(name);

        // Ensure owner is always a member
        this.members.add(this.owner);
    }

    public UUID getId() {
        return id;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public String getName() {
        return name;
    }

    /**
     * Keeps it safe even if null/blank is passed.
     * Strict validation belongs in TeamNameValidator / service layer.
     */
    public void setName(String name) {
        this.name = sanitizeName(name);
    }

    public UUID getOwner() {
        return owner;
    }

    /**
     * Transfers ownership. Guarantees the new owner is a member.
     */
    public void setOwner(UUID owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.members.add(this.owner);
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

    /** Convenience helper if you want it. Returns the previous (sanitized) name. */
    public String renameTo(String newName) {
        final String old = this.name;
        setName(newName);
        return old;
    }

    private static String sanitizeName(String input) {
        if (input == null) return DEFAULT_NAME;

        final String cleaned = input.trim().replaceAll("\\s{2,}", " ");
        return cleaned.isBlank() ? DEFAULT_NAME : cleaned;
    }
}
