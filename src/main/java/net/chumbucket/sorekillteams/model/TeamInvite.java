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

        final String tn = (teamName == null) ? "" : teamName.trim();
        this.teamName = tn.isBlank() ? "Team" : tn;

        this.inviter = Objects.requireNonNull(inviter, "inviter");
        this.target = Objects.requireNonNull(target, "target");

        final long now = System.currentTimeMillis();
        this.createdAtMs = createdAtMs > 0 ? createdAtMs : now;

        // Ensure expires is never "before created" (can happen if config changes or bad data)
        long exp = expiresAtMs > 0 ? expiresAtMs : (this.createdAtMs + 1L);
        if (exp < this.createdAtMs) exp = this.createdAtMs;
        this.expiresAtMs = exp;
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

    /**
     * Seconds remaining until expiry (ceil, never negative).
     * Example: 1ms remaining -> 1s (not 0s).
     */
    public long getSecondsRemaining(long nowMs) {
        long diff = expiresAtMs - nowMs;
        if (diff <= 0) return 0L;
        return (diff + 999L) / 1000L;
    }

    // Record-style compatibility (if any code used inv.teamId(), etc.)
    public UUID teamId() { return getTeamId(); }
    public String teamName() { return getTeamName(); }
    public UUID inviter() { return getInviter(); }
    public UUID target() { return getTarget(); }
    public long createdAtMs() { return getCreatedAtMs(); }
    public long expiresAtMs() { return getExpiresAtMs(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamInvite that)) return false;
        return teamId.equals(that.teamId)
                && inviter.equals(that.inviter)
                && target.equals(that.target)
                && createdAtMs == that.createdAtMs
                && expiresAtMs == that.expiresAtMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, inviter, target, createdAtMs, expiresAtMs);
    }

    @Override
    public String toString() {
        return "TeamInvite{" +
                "teamId=" + teamId +
                ", teamName='" + teamName + '\'' +
                ", inviter=" + inviter +
                ", target=" + target +
                ", createdAtMs=" + createdAtMs +
                ", expiresAtMs=" + expiresAtMs +
                '}';
    }
}
