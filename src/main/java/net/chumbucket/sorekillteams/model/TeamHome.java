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

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class TeamHome {

    private static final String DEFAULT_SERVER = "default";

    private final UUID teamId;

    // stored normalized key (lowercase)
    private final String name;

    // display name as entered (for chat)
    private final String displayName;

    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    private final long createdAtMs;
    private final UUID createdBy;

    // for proxy networks
    private final String serverName;

    public TeamHome(UUID teamId,
                    String name,
                    String displayName,
                    String world,
                    double x, double y, double z,
                    float yaw, float pitch,
                    long createdAtMs,
                    UUID createdBy,
                    String serverName) {

        this.teamId = Objects.requireNonNull(teamId, "teamId");

        final String norm = normalizeKey(name);
        this.name = norm;
        this.displayName = sanitizeDisplayName(displayName, norm);

        this.world = sanitizeWorld(world);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;

        this.createdAtMs = createdAtMs > 0 ? createdAtMs : System.currentTimeMillis();
        this.createdBy = createdBy; // nullable is fine (older data / console, etc.)
        this.serverName = sanitizeServer(serverName);
    }

    public UUID getTeamId() { return teamId; }

    /** Normalized key (what commands should match against). */
    public String getName() { return name; }

    /** Pretty name (what you show to players). */
    public String getDisplayName() { return displayName; }

    public String getWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }

    public long getCreatedAtMs() { return createdAtMs; }
    public UUID getCreatedBy() { return createdBy; }

    public String getServerName() { return serverName; }

    public Location toLocationOrNull() {
        if (world.isBlank()) return null;

        final World w = Bukkit.getWorld(world);
        if (w == null) return null;

        return new Location(w, x, y, z, yaw, pitch);
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    private static String sanitizeDisplayName(String displayName, String fallback) {
        if (displayName == null) return fallback;
        final String cleaned = displayName.trim().replaceAll("\\s{2,}", " ");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String sanitizeWorld(String world) {
        if (world == null) return "";
        return world.trim();
    }

    private static String sanitizeServer(String serverName) {
        if (serverName == null) return DEFAULT_SERVER;
        final String cleaned = serverName.trim();
        return cleaned.isBlank() ? DEFAULT_SERVER : cleaned;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamHome that)) return false;
        return Objects.equals(teamId, that.teamId) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, name);
    }
}
