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

import java.util.Locale;

public final class Debug {

    private static final String PREFIX = "[DBG] ";

    private final SorekillTeamsPlugin plugin;

    public Debug(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Global debug toggle.
     * Uses debug.enabled first, falls back to chat.debug for backwards compat.
     */
    public boolean enabled() {
        if (plugin == null) return false;
        try {
            if (plugin.getConfig().contains("debug.enabled")) {
                return plugin.getConfig().getBoolean("debug.enabled", false);
            }
            // backwards compat
            return plugin.getConfig().getBoolean("chat.debug", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public void log(String message) {
        if (!enabled() || isBlank(message) || plugin == null) return;
        plugin.getLogger().info(PREFIX + message);
    }

    public void warn(String message) {
        if (!enabled() || isBlank(message) || plugin == null) return;
        plugin.getLogger().warning(PREFIX + message);
    }

    public void error(String message) {
        if (!enabled() || isBlank(message) || plugin == null) return;
        plugin.getLogger().severe(PREFIX + message);
    }

    public void error(String message, Throwable t) {
        if (!enabled() || plugin == null) return;
        if (!isBlank(message)) plugin.getLogger().severe(PREFIX + message);
        if (t != null) plugin.getLogger().severe(PREFIX + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Optional: small helper for consistent debug formatting. */
    public static String fmt(String template, Object... args) {
        if (template == null) return "";
        try {
            return String.format(Locale.ROOT, template, args);
        } catch (Exception ignored) {
            return template;
        }
    }
}
