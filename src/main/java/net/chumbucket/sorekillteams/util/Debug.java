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

public final class Debug {

    private final SorekillTeamsPlugin plugin;

    public Debug(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        try {
            return plugin.getConfig().getBoolean("chat.debug", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public void log(String message) {
        if (!enabled()) return;
        if (message == null) return;
        plugin.getLogger().info("[DBG] " + message);
    }

    public void warn(String message) {
        if (!enabled()) return;
        if (message == null) return;
        plugin.getLogger().warning("[DBG] " + message);
    }
}