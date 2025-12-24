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

    private final UUID teamId;

    // stored normalized key (lowercase)
    private final String name;

    // display name as entered (for chat)
    private final String displayName;

    private final String world;
    private final double x, y, z;
    private final float yaw, pitch;

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

        this.teamId = teamId;

        String norm = normalize(name);
        this.name = norm;
        this.displayName = (displayName == null || displayName.isBlank()) ? norm : displayName.trim();

        this.world = world == null ? "" : world.trim();
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;

        this.createdAtMs = createdAtMs;
        this.createdBy = createdBy;
        this.serverName = serverName == null ? "" : serverName.trim();
    }

    public UUID getTeamId() { return teamId; }
    public String getName() { return name; }
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
        if (world == null || world.isBlank()) return null;
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, pitch);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
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
