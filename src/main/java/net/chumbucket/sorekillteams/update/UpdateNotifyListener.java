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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateNotifyListener implements Listener {

    private final SorekillTeamsPlugin plugin;
    private final UpdateChecker checker;

    public UpdateNotifyListener(SorekillTeamsPlugin plugin, UpdateChecker checker) {
        this.plugin = plugin;
        this.checker = checker;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("update_checker.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("update_checker.notify_ops_on_join", true)) return;

        var p = e.getPlayer();
        if (!p.isOp()) return;

        if (checker == null) return;

        checker.getLastResult().ifPresent(res -> {
            if (!res.success()) return;
            if (!res.updateAvailable()) return;

            p.sendMessage(plugin.msg().format(
                    "update_notify_op",
                    "{latest}", res.latestVersion(),
                    "{current}", res.currentVersion(),
                    "{url}", res.url()
            ));
        });
    }
}
