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
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {

    public record Result(
            boolean success,
            boolean updateAvailable,
            String currentVersion,
            String latestVersion,
            String url,
            String error
    ) {}

    private static final String CFG_ENABLED = "update_checker.enabled";

    private static final String CFG_HISTORY_URL = "update_checker.spigot.history_url";
    private static final String CFG_RESOURCE_ID = "update_checker.spigot.resource_id";
    private static final String CFG_SLUG = "update_checker.spigot.slug";

    // Cache window (30 minutes) - matches HuskHomesMenus
    private static final long CACHE_MS = 30L * 60L * 1000L;

    // Spiget versions/latest response includes "name":"1.1.6"
    private static final Pattern NAME_FIELD =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final SorekillTeamsPlugin plugin;

    private volatile String cachedLatest = null;
    private volatile long lastCheckMs = 0L;

    // store last full result (used by older code paths if any)
    private final AtomicReference<Result> lastResult = new AtomicReference<>();

    public UpdateChecker(SorekillTeamsPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public Optional<Result> getLastResult() {
        return Optional.ofNullable(lastResult.get());
    }

    /**
     * Old call-site compatibility (your current code calls this from elsewhere).
     * This now performs a real async check and logs to console similarly to before.
     */
    public void checkNowAsync() {
        if (!plugin.getConfig().getBoolean(CFG_ENABLED, true)) return;

        checkNowAsyncResult().thenAccept(res -> {
            if (res == null) return;

            // keep lastResult for any old listeners/logic
            lastResult.set(res);

            if (!res.success()) {
                String msg = plugin.msg().format("update_check_failed", "{reason}", safe(res.error()));
                plugin.getLogger().warning(stripForConsole(msg));
                return;
            }

            if (res.updateAvailable()) {
                String msg = plugin.msg().format(
                        "update_outdated_console",
                        "{latest}", safe(res.latestVersion()),
                        "{current}", safe(res.currentVersion()),
                        "{url}", safe(res.url())
                );
                plugin.getLogger().warning(stripForConsole(msg));
            } else {
                String msg = plugin.msg().format(
                        "update_up_to_date_console",
                        "{current}", safe(res.currentVersion())
                );
                plugin.getLogger().info(stripForConsole(msg));
            }
        });
    }

    /**
     * Always performs a network request (Spiget), updates cache, returns Result.
     */
    public CompletableFuture<Result> checkNowAsyncResult() {
        if (!plugin.getConfig().getBoolean(CFG_ENABLED, true)) {
            return CompletableFuture.completedFuture(
                    new Result(false, false, getCurrentVersionBestEffort(), safe(cachedLatest), resolveUrlForPlayers(), "Update checker disabled")
            );
        }

        final int resourceId = plugin.getConfig().getInt(CFG_RESOURCE_ID, -1);
        final String current = safe(getCurrentVersionBestEffort());
        final String url = resolveUrlForPlayers();

        if (resourceId <= 0) {
            return CompletableFuture.completedFuture(
                    new Result(false, false, current, safe(cachedLatest), url, "Missing config: " + CFG_RESOURCE_ID)
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                final String latest = safe(fetchLatestVersionFromSpiget(resourceId));

                if (!latest.isBlank()) {
                    cachedLatest = latest;
                    lastCheckMs = System.currentTimeMillis();
                }

                if (latest.isBlank() || current.isBlank()) {
                    return new Result(false, false, current, latest, url, "Could not resolve current/latest version");
                }

                final String normCurrent = normalizeVersion(current);
                final String normLatest = normalizeVersion(latest);

                boolean updateAvailable = VersionUtil.isNewer(normLatest, normCurrent);
                return new Result(true, updateAvailable, normCurrent, normLatest, url, "");

            } catch (Throwable t) {
                plugin.getLogger().warning("[UpdateChecker] Failed to check updates: "
                        + t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
                return new Result(false, false, current, safe(cachedLatest), url,
                        t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
            }
        });
    }

    /**
     * Uses cachedLatest if fresh; otherwise does a network check.
     */
    public CompletableFuture<Result> checkIfNeededAsync() {
        if (!plugin.getConfig().getBoolean(CFG_ENABLED, true)) {
            return CompletableFuture.completedFuture(
                    new Result(false, false, getCurrentVersionBestEffort(), safe(cachedLatest), resolveUrlForPlayers(), "Update checker disabled")
            );
        }

        long now = System.currentTimeMillis();
        String current = safe(getCurrentVersionBestEffort());
        String latest = safe(cachedLatest);
        String url = resolveUrlForPlayers();

        if (!latest.isBlank() && (now - lastCheckMs) < CACHE_MS) {
            if (current.isBlank()) {
                return CompletableFuture.completedFuture(new Result(false, false, current, latest, url, "Missing current version"));
            }

            String normCurrent = normalizeVersion(current);
            String normLatest = normalizeVersion(latest);

            boolean updateAvailable = VersionUtil.isNewer(normLatest, normCurrent);
            Result res = new Result(true, updateAvailable, normCurrent, normLatest, url, "");
            lastResult.set(res);
            return CompletableFuture.completedFuture(res);
        }

        return checkNowAsyncResult().thenApply(res -> {
            if (res != null) lastResult.set(res);
            return res;
        });
    }

    /**
     * Sends the player message only if current < latest.
     * Uses your messages.yml formatting system.
     */
    public void notifyPlayerIfOutdated(Player p, String permission) {
        if (p == null) return;
        if (permission != null && !permission.isBlank() && !p.hasPermission(permission)) return;

        String current = safe(getCurrentVersionBestEffort());
        String latest = safe(cachedLatest);
        if (current.isBlank() || latest.isBlank()) return;

        String normCurrent = normalizeVersion(current);
        String normLatest = normalizeVersion(latest);

        if (!VersionUtil.isNewer(normLatest, normCurrent)) return;

        String url = resolveUrlForPlayers();

        p.sendMessage(plugin.msg().format(
                "update_notify_op",
                "{latest}", normLatest,
                "{current}", normCurrent,
                "{url}", url
        ));
    }

    private String fetchLatestVersionFromSpiget(int resourceId) throws Exception {
        URI uri = URI.create("https://api.spiget.org/v2/resources/" + resourceId + "/versions/latest");
        URL url = uri.toURL();

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout((int) Duration.ofSeconds(6).toMillis());
        con.setReadTimeout((int) Duration.ofSeconds(6).toMillis());
        con.setRequestProperty("User-Agent", "SorekillTeams Update Checker");

        int code = con.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            String json = sb.toString();

            Matcher m = NAME_FIELD.matcher(json);
            if (!m.find()) {
                plugin.getLogger().warning("[UpdateChecker] Could not parse latest version from Spiget response: "
                        + (json.length() > 250 ? json.substring(0, 250) + "..." : json));
                return "";
            }
            return safe(m.group(1));
        }
    }

    private String resolveUrlForPlayers() {
        // Prefer explicitly configured history url (matches your config template)
        String direct = safe(plugin.getConfig().getString(CFG_HISTORY_URL, ""));
        if (!direct.isBlank()) return direct;

        // Otherwise build from resource id (+ optional slug)
        int id = plugin.getConfig().getInt(CFG_RESOURCE_ID, -1);
        if (id <= 0) return "";

        String slug = safe(plugin.getConfig().getString(CFG_SLUG, "sorekillteams"));
        if (slug.isBlank()) slug = "sorekillteams";

        // Keep your old behavior: point to history page
        return "https://www.spigotmc.org/resources/" + slug + "." + id + "/history";
    }

    private String getCurrentVersionBestEffort() {
        // Paper plugin meta first (when available), then fallback to description
        try {
            Object meta = plugin.getClass().getMethod("getPluginMeta").invoke(plugin);
            if (meta != null) {
                Object v = meta.getClass().getMethod("getVersion").invoke(meta);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) { }

        try {
            return plugin.getDescription().getVersion();
        } catch (Throwable ignored) { }

        return "";
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v.trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * messages.yml uses & color codes and Msg.format() translates them into § codes.
     * Console doesn't render §, so we strip them for clean logs.
     */
    private static String stripForConsole(String s) {
        if (s == null) return "";
        String colored = Msg.color(s);
        return ChatColor.stripColor(colored);
    }
}
