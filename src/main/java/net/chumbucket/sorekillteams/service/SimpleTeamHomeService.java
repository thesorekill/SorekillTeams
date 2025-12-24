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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale; // ✅ FIX: needed for Locale.ROOT
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleTeamHomeService implements TeamHomeService {

    // teamId -> (homeName -> TeamHome)
    private final Map<UUID, Map<String, TeamHome>> homes = new ConcurrentHashMap<>();

    // teamId -> last teleport ms
    private final Map<UUID, Long> lastTeleportMs = new ConcurrentHashMap<>();

    @Override
    public void putLoadedHome(TeamHome home) {
        if (home == null || home.getTeamId() == null) return;
        String key = normalize(home.getName());
        if (key.isBlank()) return;

        homes.computeIfAbsent(home.getTeamId(), __ -> new ConcurrentHashMap<>())
                .put(key, home);
    }

    @Override
    public void clearAll() {
        homes.clear();
        lastTeleportMs.clear();
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
        if (inner == null) return Optional.empty();

        return Optional.ofNullable(inner.get(key));
    }

    @Override
    public boolean setHome(TeamHome home, int maxHomes) {
        if (home == null || home.getTeamId() == null) return false;

        UUID teamId = home.getTeamId();
        String key = normalize(home.getName());
        if (key.isBlank()) return false;

        Map<String, TeamHome> inner = homes.computeIfAbsent(teamId, __ -> new ConcurrentHashMap<>());
        boolean exists = inner.containsKey(key);

        if (!exists) {
            int max = Math.max(1, maxHomes);
            if (inner.size() >= max) return false;
        }

        inner.put(key, home);
        return true;
    }

    @Override
    public boolean deleteHome(UUID teamId, String name) {
        if (teamId == null) return false;
        String key = normalize(name);
        if (key.isBlank()) return false;

        Map<String, TeamHome> inner = homes.get(teamId);
        if (inner == null) return false;

        TeamHome removed = inner.remove(key);
        if (inner.isEmpty()) homes.remove(teamId);

        return removed != null;
    }

    @Override
    public void clearTeam(UUID teamId) {
        if (teamId == null) return;
        homes.remove(teamId);
        lastTeleportMs.remove(teamId);
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
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }
}