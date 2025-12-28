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

    private static final long JOIN_CHECK_COOLDOWN_MS = 60_000L;

    private final SorekillTeamsPlugin plugin;
    private final UpdateChecker checker;

    // Prevent spam checks if many ops join at once
    private volatile long lastJoinTriggeredCheckMs = 0L;

    public UpdateNotifyListener(SorekillTeamsPlugin plugin, UpdateChecker checker) {
        this.plugin = plugin;
        this.checker = checker;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        if (checker == null) return;

        if (!plugin.getConfig().getBoolean("update_checker.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("update_checker.notify_ops_on_join", true)) return;

        final var p = e.getPlayer();
        if (p == null || !p.hasPermission("sorekillteams.admin")) return;

        // If we don't have a cached result yet (fresh boot), optionally trigger a check.
        // This avoids "no notification until next scheduled check".
        var lastOpt = checker.getLastResult();
        if (lastOpt.isEmpty()) {
            maybeTriggerCheck();
            return;
        }

        var res = lastOpt.get();
        if (!res.success() || !res.updateAvailable()) return;

        String url = safeUrl(res.url());
        if (url.isBlank()) {
            // Spigot config fallback
            url = safeUrl(plugin.getConfig().getString("update_checker.spigot.history_url", ""));
        }
        if (url.isBlank()) {
            // final fallback: compute from resource id/slug if your UpdateChecker supports it
            // (If not, this will remain blank and message can omit {url} gracefully.)
            url = "";
        }

        p.sendMessage(plugin.msg().format(
                "update_notify_op",
                "{latest}", safe(res.latestVersion()),
                "{current}", safe(res.currentVersion()),
                "{url}", url
        ));
    }

    private void maybeTriggerCheck() {
        long now = System.currentTimeMillis();
        if (now - lastJoinTriggeredCheckMs < JOIN_CHECK_COOLDOWN_MS) return;

        lastJoinTriggeredCheckMs = now;
        checker.checkNowAsync();
    }

    private static String safeUrl(String s) {
        String v = safe(s);
        // avoid sending literal "null" in chat
        return "null".equalsIgnoreCase(v) ? "" : v;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
