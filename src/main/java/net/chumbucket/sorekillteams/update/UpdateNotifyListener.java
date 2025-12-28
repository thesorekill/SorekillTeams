/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.update;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateNotifyListener implements Listener {

    private final SorekillTeamsPlugin plugin;
    private final UpdateChecker checker;

    public UpdateNotifyListener(SorekillTeamsPlugin plugin, UpdateChecker checker) {
        this.plugin = plugin;
        this.checker = checker;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (checker == null) return;

        if (!plugin.getConfig().getBoolean("update_checker.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("update_checker.notify_ops_on_join", true)) return;

        final Player p = e.getPlayer();
        if (p == null) return;

        // Only notify admins/ops (same behavior as before)
        if (!p.hasPermission("sorekillteams.admin")) return;

        // ✅ Actually check (uses cache when available), then notify on main thread
        checker.checkIfNeededAsync().thenAccept(result -> {
            if (result == null) return;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;

                // notify only if outdated
                checker.notifyPlayerIfOutdated(p, "sorekillteams.admin");
            });
        });
    }
}
