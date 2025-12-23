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
 *
 * Prevents duplicates, supports expiry, and purges automatically.
 */
public final class TeamInvites {

    private final Map<UUID, Map<UUID, TeamInvite>> invitesByTarget = new ConcurrentHashMap<>();

    public List<TeamInvite> listActive(UUID target, long nowMs) {
        purgeExpired(target, nowMs);
        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null) return List.of();
        return new ArrayList<>(inner.values());
    }

    public Optional<TeamInvite> get(UUID target, UUID teamId, long nowMs) {
        purgeExpired(target, nowMs);
        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null) return Optional.empty();
        return Optional.ofNullable(inner.get(teamId));
    }

    /** Returns true if created; false if duplicate already exists (and not expired). */
    public boolean create(TeamInvite invite, long nowMs) {
        purgeExpired(invite.getTarget(), nowMs);

        Map<UUID, TeamInvite> inner =
                invitesByTarget.computeIfAbsent(invite.getTarget(), k -> new ConcurrentHashMap<>());

        // Duplicate check: same team -> same target
        if (inner.containsKey(invite.getTeamId())) return false;

        inner.put(invite.getTeamId(), invite);
        return true;
    }

    public boolean remove(UUID target, UUID teamId) {
        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null) return false;

        TeamInvite removed = inner.remove(teamId);
        if (inner.isEmpty()) invitesByTarget.remove(target);

        return removed != null;
    }

    /** @return number of invites purged for this target */
    public int purgeExpired(UUID target, long nowMs) {
        Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
        if (inner == null) return 0;

        int before = inner.size();
        inner.values().removeIf(inv -> inv.isExpired(nowMs));
        int after = inner.size();

        if (after == 0) invitesByTarget.remove(target);
        return before - after;
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
        for (UUID target : new ArrayList<>(invitesByTarget.keySet())) {
            Map<UUID, TeamInvite> inner = invitesByTarget.get(target);
            if (inner == null) continue;

            inner.remove(teamId);
            if (inner.isEmpty()) invitesByTarget.remove(target);
        }
    }

    public void clearTarget(UUID target) {
        invitesByTarget.remove(target);
    }

    public String debugSummary() {
        return invitesByTarget.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().size())
                .collect(Collectors.joining(", "));
    }
}
