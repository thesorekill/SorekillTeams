/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.util;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks /team home cooldowns per-player (not team-wide).
 * Intentionally in-memory; if you ever want persistence, do it elsewhere.
 */
public final class TeamHomeCooldowns {

    // Cooldown bypass permission for /team home
    public static final String BYPASS_PERMISSION = "sorekillteams.home.bypasscooldown";

    private final SorekillTeamsPlugin plugin;
    private final Map<UUID, Long> lastTeleportMsByPlayer = new ConcurrentHashMap<>();

    public TeamHomeCooldowns(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks if the player passes cooldown (and sends the configured cooldown message on failure).
     * Respects BYPASS_PERMISSION automatically.
     */
    public boolean passes(Player p) {
        if (p == null) return false;
        if (p.hasPermission(BYPASS_PERMISSION)) return true;

        long remaining = getRemainingSeconds(p.getUniqueId());
        if (remaining > 0) {
            plugin.msg().send(p, "team_home_cooldown", "{seconds}", String.valueOf(remaining));
            return false;
        }
        return true;
    }

    /**
     * Returns remaining cooldown seconds for a player UUID.
     * 0 means no cooldown active (or cooldown disabled).
     */
    public long getRemainingSeconds(UUID playerId) {
        if (playerId == null) return 0L;

        int cooldownSeconds = Math.max(0, plugin.getConfig().getInt("homes.cooldown_seconds", 0));
        if (cooldownSeconds <= 0) return 0L;

        long now = System.currentTimeMillis();
        long last = lastTeleportMsByPlayer.getOrDefault(playerId, 0L);
        long waitMs = cooldownSeconds * 1000L;

        long elapsed = now - last;
        if (elapsed >= waitMs) return 0L;

        // ceil to next whole second
        return (waitMs - elapsed + 999) / 1000;
    }

    public void markTeleported(Player p) {
        if (p == null) return;
        lastTeleportMsByPlayer.put(p.getUniqueId(), System.currentTimeMillis());
    }

    public void clear(Player p) {
        if (p == null) return;
        lastTeleportMsByPlayer.remove(p.getUniqueId());
    }

    public void clear(UUID playerId) {
        if (playerId == null) return;
        lastTeleportMsByPlayer.remove(playerId);
    }
}
