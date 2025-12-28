/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.listener;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Option 3: SQL refresh on join.
 *
 * Fixes two kinds of staleness:
 *  1) Player membership cache (is player in a team?)
 *  2) Global teams cache (browse lists / lookups can still show teams deleted elsewhere)
 *
 * Both refreshes do async DB work and apply results back on the main thread.
 */
public final class SqlBackfillJoinListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public SqlBackfillJoinListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        if (plugin == null || e == null || e.getPlayer() == null) return;

        // 1) Refresh membership (loads team if present, clears stale membership mapping if not)
        plugin.ensureTeamFreshFromSql(e.getPlayer());

        // 2) Refresh global teams snapshot (removes disbanded teams from THIS backend's cache)
        // This must exist in SorekillTeamsPlugin (TTL guarded) and must NOT mark dirty.
        plugin.ensureTeamsSnapshotFreshFromSql();
    }
}
