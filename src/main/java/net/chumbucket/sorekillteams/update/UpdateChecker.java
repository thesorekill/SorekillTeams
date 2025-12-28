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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
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

    // Version table text contains: "1.1.1 Dec 23, 2025 at 4:05 PM"
    private static final Pattern TABLE_VERSION = Pattern.compile(
            "\\b([0-9]+(?:\\.[0-9]+){0,2})\\b\\s+([A-Z][a-z]{2})\\s+\\d{1,2},\\s+\\d{4}",
            Pattern.CASE_INSENSITIVE
    );

    // Generic semver-ish token matcher
    private static final Pattern ANY_VERSION = Pattern.compile("\\b([0-9]+(?:\\.[0-9]+){0,2})\\b");

    private final SorekillTeamsPlugin plugin;
    private final HttpClient http;

    // store last result so join listener can notify ops
    private final AtomicReference<Result> lastResult = new AtomicReference<>();

    public UpdateChecker(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Optional<Result> getLastResult() {
        return Optional.ofNullable(lastResult.get());
    }

    public void checkNowAsync() {
        if (!plugin.getConfig().getBoolean(CFG_ENABLED, true)) return;

        final String url = resolveHistoryUrl();
        if (url.isBlank()) {
            plugin.getLogger().warning("[UpdateChecker] Skipping update check: update_checker.spigot.history_url/resource_id not set");
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Result res = checkSpigot(url);
            lastResult.set(res);

            if (!res.success()) {
                String msg = plugin.msg().format("update_check_failed", "{reason}", res.error());
                plugin.getLogger().warning(stripForConsole(msg));
                return;
            }

            if (res.updateAvailable()) {
                String msg = plugin.msg().format(
                        "update_outdated_console",
                        "{latest}", res.latestVersion(),
                        "{current}", res.currentVersion(),
                        "{url}", res.url()
                );
                plugin.getLogger().warning(stripForConsole(msg));
            } else {
                String msg = plugin.msg().format(
                        "update_up_to_date_console",
                        "{current}", res.currentVersion()
                );
                plugin.getLogger().info(stripForConsole(msg));
            }
        });
    }

    private Result checkSpigot(String historyUrl) {
        final String current = normalizeVersion(plugin.getDescription().getVersion());

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(historyUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "SorekillTeams/" + current + " (UpdateChecker)")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();

            if (code != 200 || body.isBlank()) {
                String snippet = body.length() > 160 ? body.substring(0, 160) + "..." : body;
                return new Result(false, false, current, "", historyUrl, "HTTP " + code + " (" + snippet + ")");
            }

            String latest = extractLatestVersion(body).orElse("");
            if (latest.isBlank()) {
                return new Result(false, false, current, "", historyUrl, "Could not parse latest version from Spigot page");
            }

            latest = normalizeVersion(latest);
            boolean updateAvailable = VersionUtil.isNewer(latest, current);

            return new Result(true, updateAvailable, current, latest, historyUrl, "");
        } catch (Exception e) {
            return new Result(false, false, current, "", historyUrl,
                    e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private Optional<String> extractLatestVersion(String html) {
        if (html == null || html.isBlank()) return Optional.empty();

        // 1) Try the Version History table — newest is typically listed first
        Matcher table = TABLE_VERSION.matcher(html);
        if (table.find()) {
            String v = table.group(1);
            if (v != null && !v.isBlank()) return Optional.of(v);
        }

        // 2) Robust fallback: strip tags, scan all versions, pick newest
        String text = stripHtmlTags(html);

        Matcher any = ANY_VERSION.matcher(text);
        String best = "";
        while (any.find()) {
            String cand = any.group(1);
            if (cand == null || cand.isBlank()) continue;

            String norm = normalizeVersion(cand);
            if (best.isBlank() || VersionUtil.isNewer(norm, best)) best = norm;
        }

        return best.isBlank() ? Optional.empty() : Optional.of(best);
    }

    private static String stripHtmlTags(String html) {
        // Fast + dependency-free (good enough for parsing).
        // Also strips script/style blocks to avoid version numbers in JS/CSS.
        String s = html.replaceAll("(?is)<script.*?>.*?</script>", " ");
        s = s.replaceAll("(?is)<style.*?>.*?</style>", " ");
        s = s.replaceAll("(?is)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ");
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    private String resolveHistoryUrl() {
        // Option A (preferred): hard-set the full history URL
        String direct = safeTrim(plugin.getConfig().getString(CFG_HISTORY_URL, ""));
        if (!direct.isBlank()) return direct;

        // Option B: build from resource id (+ optional slug)
        int id = plugin.getConfig().getInt(CFG_RESOURCE_ID, -1);
        if (id <= 0) return "";

        String slug = safeTrim(plugin.getConfig().getString(CFG_SLUG, "sorekillteams"));
        if (slug.isBlank()) slug = "sorekillteams";

        return "https://www.spigotmc.org/resources/" + slug + "." + id + "/history";
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
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
