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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory invite store:
 * targetUuid -> (teamId -> invite)
 */
public final class TeamInvites {

    private final Map<UUID, Map<UUID, TeamInvite>> invitesByTarget = new ConcurrentHashMap<>();

    // Hard cap to prevent invite spam + memory growth.
    private static final int MAX_INVITES_PER_TARGET = 25;

    /* ------------------------------------------------------------
     * Convenience overloads (compat)
     * ------------------------------------------------------------ */

    public List<TeamInvite> listActive(UUID target) {
        return listActive(target, System.currentTimeMillis());
    }

    public Optional<TeamInvite> get(UUID target, UUID teamId) {
        return get(target, teamId, System.currentTimeMillis());
    }

    public boolean create(TeamInvite invite) {
        return create(invite, System.currentTimeMillis());
    }

    public int purgeExpired(UUID target) {
        return purgeExpired(target, System.currentTimeMillis());
    }

    public int purgeExpiredAll() {
        return purgeExpiredAll(System.currentTimeMillis());
    }

    /* ------------------------------------------------------------
     * Primary APIs
     * ------------------------------------------------------------ */

    public List<TeamInvite> listActive(UUID target, long nowMs) {
        if (target == null) return List.of();

        purgeExpired(target, nowMs);

        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null || inner.isEmpty()) return List.of();

        return inner.values().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingLong(TeamInvite::getExpiresAtMs)
                        .thenComparing(inv -> inv.getTeamId().toString()))
                .collect(Collectors.toList());
    }

    public Optional<TeamInvite> get(UUID target, UUID teamId, long nowMs) {
        if (target == null || teamId == null) return Optional.empty();

        purgeExpired(target, nowMs);

        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null) return Optional.empty();

        TeamInvite inv = inner.get(teamId);
        if (inv == null) return Optional.empty();

        if (inv.isExpired(nowMs)) {
            remove(target, teamId);
            return Optional.empty();
        }

        return Optional.of(inv);
    }

    /**
     * Returns true if created; false if duplicate already exists (and not expired).
     */
    public boolean create(TeamInvite invite, long nowMs) {
        if (invite == null) return false;

        UUID target = invite.getTarget();
        UUID teamId = invite.getTeamId();
        if (target == null || teamId == null) return false;

        purgeExpired(target, nowMs);

        Map<UUID, TeamInvite> inner =
                invitesByTarget.computeIfAbsent(target, __ -> new ConcurrentHashMap<>());

        TeamInvite existing = inner.get(teamId);
        if (existing != null && !existing.isExpired(nowMs)) {
            return false;
        }

        // Cap invites per target; evict soonest-to-expire to make room
        if (inner.size() >= MAX_INVITES_PER_TARGET) {
            UUID evictId = inner.values().stream()
                    .filter(Objects::nonNull)
                    .min(Comparator
                            .comparingLong(TeamInvite::getExpiresAtMs)
                            .thenComparing(i -> i.getTeamId().toString()))
                    .map(TeamInvite::getTeamId)
                    .orElse(null);

            if (evictId != null) inner.remove(evictId);

            if (inner.size() >= MAX_INVITES_PER_TARGET) {
                cleanupIfEmpty(target, inner);
                return false;
            }
        }

        inner.put(teamId, invite);
        return true;
    }

    public boolean remove(UUID target, UUID teamId) {
        if (target == null || teamId == null) return false;

        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null) return false;

        TeamInvite removed = inner.remove(teamId);
        cleanupIfEmpty(target, inner);

        return removed != null;
    }

    /** @return number of invites purged for this target */
    public int purgeExpired(UUID target, long nowMs) {
        if (target == null) return 0;

        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null || inner.isEmpty()) return 0;

        int purged = 0;

        // Safer than values().removeIf(...) for CHM views across forks/JDKs:
        for (Map.Entry<UUID, TeamInvite> entry : inner.entrySet()) {
            UUID teamId = entry.getKey();
            TeamInvite inv = entry.getValue();

            if (teamId == null || inv == null || inv.isExpired(nowMs)) {
                // remove only if still mapped to the same value
                if (teamId != null && inner.remove(teamId, inv)) {
                    purged++;
                } else if (teamId == null) {
                    // very rare: null key shouldn't happen, but don't trust it
                    purged++;
                }
            }
        }

        cleanupIfEmpty(target, inner);
        return purged;
    }

    /** @return number of invites purged across all targets */
    public int purgeExpiredAll(long nowMs) {
        int purged = 0;
        for (UUID target : new ArrayList<>(invitesByTarget.keySet())) {
            purged += purgeExpired(target, nowMs);
        }
        return purged;
    }

    public void clearTeam(UUID teamId) {
        if (teamId == null) return;

        for (UUID target : new ArrayList<>(invitesByTarget.keySet())) {
            Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
            if (inner == null || inner.isEmpty()) continue;

            inner.remove(teamId);
            cleanupIfEmpty(target, inner);
        }
    }

    public void clearTarget(UUID target) {
        if (target == null) return;
        invitesByTarget.remove(target);
    }

    /** Total invites across all targets (best-effort snapshot). */
    public int totalInvites() {
        int sum = 0;
        for (Map<UUID, TeamInvite> inner : invitesByTarget.values()) {
            if (inner != null) sum += inner.size();
        }
        return sum;
    }

    /** Number of targets with at least one invite. */
    public int targetCount() {
        return invitesByTarget.size();
    }

    public String debugSummary() {
        // stable snapshot
        List<Map.Entry<UUID, Map<UUID, TeamInvite>>> entries = new ArrayList<>(invitesByTarget.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().toString()));

        return entries.stream()
                .map(e -> e.getKey() + ":" + (e.getValue() == null ? 0 : e.getValue().size()))
                .collect(Collectors.joining(", "));
    }

    private void cleanupIfEmpty(UUID target, Map<UUID, TeamInvite> inner) {
        if (target == null || inner == null) return;
        if (inner.isEmpty()) invitesByTarget.remove(target);
    }
}