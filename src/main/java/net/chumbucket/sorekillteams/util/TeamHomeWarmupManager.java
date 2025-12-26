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
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamHome;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles actionbar warmup countdowns for team home teleports.
 * Cancels on move and cancels on hit.
 *
 * This manager ONLY manages the countdown + cancellation.
 * Teleport + cooldown marking should be handled by the caller (TeamHomeCommands).
 */
public final class TeamHomeWarmupManager implements Listener {

    @FunctionalInterface
    public interface HomeSupplier {
        TeamHome get();
    }

    private static final class WarmupSession {
        final int taskId;
        final Location startLoc;

        WarmupSession(int taskId, Location startLoc) {
            this.taskId = taskId;
            this.startLoc = startLoc;
        }
    }

    private final SorekillTeamsPlugin plugin;
    private final Actionbar actionbar;

    // Track running warmups per player so countdowns don't stack
    private final Map<UUID, WarmupSession> warmupByPlayer = new ConcurrentHashMap<>();

    // Sounds (match your chosen HuskHomes-like behavior)
    private static final Sound WARMUP_TICK_SOUND = Sound.BLOCK_NOTE_BLOCK_HAT;
    private static final Sound CANCEL_SOUND = Sound.ENTITY_ITEM_BREAK;

    public TeamHomeWarmupManager(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.actionbar = plugin.actionbar();

        // Needed so hit-cancel works
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Start a warmup countdown. When complete, refetch the home via supplier
     * and call onReady with it (may be null if it disappeared).
     */
    public void start(Player p,
                      UUID expectedTeamId,
                      int warmupSeconds,
                      HomeSupplier homeSupplier,
                      Consumer<TeamHome> onReady) {

        if (p == null) return;

        cancel(p.getUniqueId());

        final UUID playerId = p.getUniqueId();
        final Location start = p.getLocation().clone();
        final int[] remaining = new int[]{Math.max(1, warmupSeconds)};

        int taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player live = Bukkit.getPlayer(playerId);
            if (live == null || !live.isOnline()) {
                cancel(playerId);
                return;
            }

            // Still in same team?
            Team currentTeam = plugin.teams().getTeamByPlayer(playerId).orElse(null);
            if (currentTeam == null || !expectedTeamId.equals(currentTeam.getId())) {
                if (actionbar != null) actionbar.clear(live);
                cancel(playerId);
                return;
            }

            // Cancel if moved
            WarmupSession sess = warmupByPlayer.get(playerId);
            if (sess != null && hasMoved(sess.startLoc, live.getLocation())) {
                cancel(playerId);

                if (actionbar != null) actionbar.send(live, "actionbar.team_home_cancelled_move");
                live.playSound(live.getLocation(), CANCEL_SOUND, 1.0f, 1.0f);
                plugin.msg().send(live, "team_home_cancelled_move");

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (actionbar != null) actionbar.clear(live);
                }, 30L);
                return;
            }

            // Show countdown
            if (actionbar != null) {
                actionbar.send(live, "actionbar.team_home_warmup",
                        "{seconds}", String.valueOf(remaining[0]));
            }

            // Tick sound
            live.playSound(live.getLocation(), WARMUP_TICK_SOUND, 1.0f, 1.0f);

            // Finish
            if (remaining[0] <= 0) {
                TeamHome hh = null;
                try {
                    hh = homeSupplier.get();
                } catch (Exception ignored) {}

                if (actionbar != null) actionbar.clear(live);

                try {
                    onReady.accept(hh);
                } catch (Exception ignored) {}

                cancel(playerId);
                return;
            }

            remaining[0]--;
        }, 0L, 20L).getTaskId();

        warmupByPlayer.put(playerId, new WarmupSession(taskId, start));
    }

    public void cancel(UUID playerId) {
        WarmupSession session = warmupByPlayer.remove(playerId);
        if (session != null) {
            plugin.getServer().getScheduler().cancelTask(session.taskId);
        }
    }

    // ============================================================
    // Cancel warmup on hit
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;

        WarmupSession session = warmupByPlayer.get(p.getUniqueId());
        if (session == null) return;

        cancel(p.getUniqueId());

        if (actionbar != null) actionbar.send(p, "actionbar.team_home_cancelled_move");
        p.playSound(p.getLocation(), CANCEL_SOUND, 1.0f, 1.0f);
        plugin.msg().send(p, "team_home_cancelled_move");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (actionbar != null) actionbar.clear(p);
        }, 30L);
    }

    private boolean hasMoved(Location start, Location now) {
        if (start == null || now == null) return true;
        if (start.getWorld() == null || now.getWorld() == null) return true;
        if (!start.getWorld().equals(now.getWorld())) return true;

        // Only cancel on positional movement (rotation doesn't matter)
        double dx = now.getX() - start.getX();
        double dy = now.getY() - start.getY();
        double dz = now.getZ() - start.getZ();
        double distSq = (dx * dx) + (dy * dy) + (dz * dz);

        // small tolerance for jitter
        return distSq > 0.0004; // ~0.02 blocks
    }
}
