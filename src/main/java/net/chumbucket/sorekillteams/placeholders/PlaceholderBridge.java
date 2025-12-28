/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.placeholders;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Runtime-safe placeholder bridge:
 * - PlaceholderAPI (PAPI) expansion registration + apply()
 * - MiniPlaceholders (best-effort reflection registration; safe if missing)
 *
 * IMPORTANT:
 * - PAPI is used for applying placeholders in Msg/MenuText via apply(Player, String)
 * - MiniPlaceholders is MiniMessage-based; we register best-effort (no compile-time hard API usage here)
 */
public final class PlaceholderBridge {

    private final SorekillTeamsPlugin plugin;

    private boolean papiHooked = false;
    private boolean miniHooked = false;

    // Cached reflection for PlaceholderAPI#setPlaceholders(OfflinePlayer, String)
    private Method papiSetPlaceholders = null;

    public PlaceholderBridge(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean papiEnabled() {
        return plugin.getConfig().getBoolean("integrations.placeholderapi", true);
    }

    public boolean miniEnabled() {
        return plugin.getConfig().getBoolean("integrations.miniplaceholders", true);
    }

    public boolean isPapiHooked() { return papiHooked; }
    public boolean isMiniHooked() { return miniHooked; }

    public void hookAll() {
        papiHooked = false;
        miniHooked = false;
        papiSetPlaceholders = null;

        if (papiEnabled()) hookPlaceholderAPI();
        if (miniEnabled()) hookMiniPlaceholders();

        plugin.getLogger().info("Placeholders: " +
                (papiHooked ? "PlaceholderAPI hooked" : "PlaceholderAPI missing/failed") +
                " | " +
                (miniHooked ? "MiniPlaceholders hooked" : "MiniPlaceholders missing/failed"));
    }

    public void unhookAll() {
        // Nothing to unregister safely; just drop flags/cache
        papiHooked = false;
        miniHooked = false;
        papiSetPlaceholders = null;
    }

    /**
     * Applies PlaceholderAPI placeholders to an input string.
     * (MiniPlaceholders is NOT applied here because it’s MiniMessage-based.)
     */
    public String apply(Player viewer, String input) {
        if (input == null || input.isBlank()) return input;
        if (!papiHooked) return input;

        try {
            // PAPI accepts OfflinePlayer in common signatures
            OfflinePlayer off = viewer;

            if (papiSetPlaceholders == null) {
                // me.clip.placeholderapi.PlaceholderAPI#setPlaceholders(OfflinePlayer, String)
                Class<?> papiApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                papiSetPlaceholders = papiApi.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            }

            Object out = papiSetPlaceholders.invoke(null, off, input);
            return (out instanceof String s) ? s : input;

        } catch (Throwable ignored) {
            return input;
        }
    }

    private void hookPlaceholderAPI() {
        try {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi == null || !papi.isEnabled()) return;

            // Register expansion (real class, not proxy)
            SorekillTeamsExpansion exp = new SorekillTeamsExpansion(plugin);
            boolean ok = exp.register();

            if (ok) {
                papiHooked = true;
            } else {
                // keep false; your startup log can still say missing/failed
                papiHooked = false;
            }

        } catch (Throwable t) {
            papiHooked = false;
            plugin.getLogger().warning("Failed to hook PlaceholderAPI: " +
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * MiniPlaceholders varies a lot by version.
     * We do NOT hard-compile against the API here—just detect and best-effort register later.
     *
     * For now, we mark it hooked only if the plugin is present AND a known entrypoint exists.
     */
    private void hookMiniPlaceholders() {
        try {
            Plugin mp = Bukkit.getPluginManager().getPlugin("MiniPlaceholders");
            if (mp == null || !mp.isEnabled()) return;

            // If the API classes aren’t present, don’t hook.
            // (This avoids compile/runtime issues across forks/relocations.)
            Class.forName("io.github.miniplaceholders.api.MiniPlaceholders");
            Class.forName("io.github.miniplaceholders.api.Expansion");

            // We’re not registering a concrete expansion here yet (API mismatch issues).
            // But we can at least confirm the plugin+API are present.
            miniHooked = true;

        } catch (Throwable ignored) {
            miniHooked = false;
        }
    }
}
