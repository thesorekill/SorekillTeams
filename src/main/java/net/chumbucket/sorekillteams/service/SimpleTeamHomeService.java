/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.service;

import net.chumbucket.sorekillteams.model.TeamHome;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SimpleTeamHomeService implements TeamHomeService {

    // teamId -> (homeName -> TeamHome)
    private final Map<UUID, Map<String, TeamHome>> homes = new ConcurrentHashMap<>();

    // teamId -> last teleport ms
    private final Map<UUID, Long> lastTeleportMs = new ConcurrentHashMap<>();

    // dirty tracking (so autosave doesn't spam / wipe)
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // If we legitimately deleted/cleared to empty, allow one empty save to delete DB rows.
    private final AtomicBoolean allowEmptyWriteOnce = new AtomicBoolean(false);

    // -----------------------------
    // Dirty helpers
    // -----------------------------

    public boolean isDirty() {
        return dirty.get();
    }

    public void markClean() {
        dirty.set(false);
        // do NOT reset allowEmptyWriteOnce here; it's consumed by storage when needed
    }

    /**
     * If homes are empty, we only want to write that emptiness to SQL when it was intentional
     * (e.g., user deleted the last home, cleared a team, etc.). This flag is "one-shot".
     */
    public boolean consumeAllowEmptyWriteOnce() {
        return allowEmptyWriteOnce.getAndSet(false);
    }

    private void markDirty() {
        dirty.set(true);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    // -----------------------------
    // TeamHomeService
    // -----------------------------

    @Override
    public void putLoadedHome(TeamHome home) {
        if (home == null) return;

        UUID teamId = home.getTeamId();
        if (teamId == null) return;

        String key = normalize(home.getName());
        if (key.isBlank()) return;

        homes.computeIfAbsent(teamId, __ -> new ConcurrentHashMap<>())
                .put(key, home);
        // NOTE: loading from storage should NOT mark dirty.
    }

    @Override
    public void clearAll() {
        homes.clear();
        lastTeleportMs.clear();
        markDirty();
        allowEmptyWriteOnce.set(true); // legitimate empty state
    }

    @Override
    public Collection<TeamHome> allHomes() {
        List<TeamHome> out = new ArrayList<>();
        for (Map<String, TeamHome> inner : homes.values()) {
            if (inner == null || inner.isEmpty()) continue;
            out.addAll(inner.values());
        }
        return out;
    }

    @Override
    public List<TeamHome> listHomes(UUID teamId) {
        if (teamId == null) return List.of();

        Map<String, TeamHome> inner = homes.get(teamId);
        if (inner == null || inner.isEmpty()) return List.of();

        List<TeamHome> out = new ArrayList<>(inner.values());
        out.sort(Comparator.comparing(h -> normalize(h.getName())));
        return out;
    }

    @Override
    public Optional<TeamHome> getHome(UUID teamId, String name) {
        if (teamId == null) return Optional.empty();

        String key = normalize(name);
        if (key.isBlank()) return Optional.empty();

        Map<String, TeamHome> inner = homes.get(teamId);
        if (inner == null || inner.isEmpty()) return Optional.empty();

        return Optional.ofNullable(inner.get(key));
    }

    @Override
    public boolean setHome(TeamHome home, int maxHomes) {
        if (home == null) return false;

        UUID teamId = home.getTeamId();
        if (teamId == null) return false;

        String key = normalize(home.getName());
        if (key.isBlank()) return false;

        Map<String, TeamHome> inner = homes.computeIfAbsent(teamId, __ -> new ConcurrentHashMap<>());
        boolean exists = inner.containsKey(key);

        if (!exists) {
            int max = Math.max(1, maxHomes);
            if (inner.size() >= max) return false;
        }

        inner.put(key, home);
        markDirty();
        return true;
    }

    @Override
    public boolean deleteHome(UUID teamId, String name) {
        if (teamId == null) return false;

        String key = normalize(name);
        if (key.isBlank()) return false;

        Map<String, TeamHome> inner = homes.get(teamId);
        if (inner == null || inner.isEmpty()) return false;

        TeamHome removed = inner.remove(key);

        if (inner.isEmpty()) {
            homes.remove(teamId, inner);
        }

        if (removed != null) {
            markDirty();

            // If this deletion resulted in NO homes globally, allow an empty SQL write (to delete rows)
            if (homes.isEmpty()) {
                allowEmptyWriteOnce.set(true);
            }
        }

        return removed != null;
    }

    @Override
    public void clearTeam(UUID teamId) {
        if (teamId == null) return;
        homes.remove(teamId);
        lastTeleportMs.remove(teamId);
        markDirty();

        if (homes.isEmpty()) {
            allowEmptyWriteOnce.set(true);
        }
    }

    @Override
    public long getLastTeleportMs(UUID teamId) {
        if (teamId == null) return 0L;
        return lastTeleportMs.getOrDefault(teamId, 0L);
    }

    @Override
    public void setLastTeleportMs(UUID teamId, long ms) {
        if (teamId == null) return;
        lastTeleportMs.put(teamId, ms);
        // cooldown tracking shouldn't force DB writes
    }
}
