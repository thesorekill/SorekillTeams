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

import java.util.Objects;
import java.util.UUID;

public final class TeamInvite {

    private final UUID teamId;
    private final String teamName;
    private final UUID inviter;
    private final UUID target;
    private final long createdAtMs;
    private final long expiresAtMs;

    public TeamInvite(UUID teamId,
                      String teamName,
                      UUID inviter,
                      UUID target,
                      long createdAtMs,
                      long expiresAtMs) {
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.teamName = Objects.requireNonNull(teamName, "teamName");
        this.inviter = Objects.requireNonNull(inviter, "inviter");
        this.target = Objects.requireNonNull(target, "target");
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
    }

    public UUID getTeamId() { return teamId; }
    public String getTeamName() { return teamName; }
    public UUID getInviter() { return inviter; }
    public UUID getTarget() { return target; }
    public long getCreatedAtMs() { return createdAtMs; }
    public long getExpiresAtMs() { return expiresAtMs; }

    public boolean isExpired(long nowMs) {
        return nowMs >= expiresAtMs;
    }

    public long getSecondsRemaining(long nowMs) {
        long rem = (expiresAtMs - nowMs) / 1000L;
        return Math.max(0L, rem);
    }

    // Record-style compatibility (if any code used inv.teamId(), etc.)
    public UUID teamId() { return getTeamId(); }
    public String teamName() { return getTeamName(); }
    public UUID inviter() { return getInviter(); }
    public UUID target() { return getTarget(); }
    public long createdAtMs() { return getCreatedAtMs(); }
    public long expiresAtMs() { return getExpiresAtMs(); }
}