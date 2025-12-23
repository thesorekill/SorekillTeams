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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class UpdateChecker {

    public record Result(boolean success,
                         boolean updateAvailable,
                         String currentVersion,
                         String latestVersion,
                         String url,
                         String error) {}

    private final SorekillTeamsPlugin plugin;
    private final HttpClient http;

    // store last result so join listener can notify ops
    private final AtomicReference<Result> lastResult = new AtomicReference<>();

    public UpdateChecker(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Optional<Result> getLastResult() {
        return Optional.ofNullable(lastResult.get());
    }

    public void checkNowAsync() {
        if (!plugin.getConfig().getBoolean("update_checker.enabled", true)) return;

        String owner = plugin.getConfig().getString("update_checker.github.owner", "").trim();
        String repo = plugin.getConfig().getString("update_checker.github.repo", "").trim();

        if (owner.isEmpty() || repo.isEmpty() || owner.equalsIgnoreCase("YOUR_GITHUB_USERNAME_OR_ORG")) {
            plugin.getLogger().warning("[UpdateChecker] Skipping update check: update_checker.github.owner/repo not set");
            return;
        }

        // async to avoid blocking main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Result res = check(owner, repo);
            lastResult.set(res);

            if (!res.success()) {
                plugin.getLogger().warning(plugin.msg().format("update_check_failed", "{reason}", res.error()));
                return;
            }

            if (res.updateAvailable()) {
                plugin.getLogger().warning(plugin.msg().format(
                        "update_outdated_console",
                        "{latest}", res.latestVersion(),
                        "{current}", res.currentVersion(),
                        "{url}", res.url()
                ));
            } else {
                plugin.getLogger().info(plugin.msg().format(
                        "update_up_to_date_console",
                        "{current}", res.currentVersion()
                ));
            }
        });
    }

    private Result check(String owner, String repo) {
        String current = normalizeVersion(plugin.getDescription().getVersion());

        try {
            // GitHub API: latest release
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SorekillTeams-UpdateChecker")
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();

            if (code != 200) {
                return new Result(false, false, current, "", "", "HTTP " + code);
            }

            // minimal JSON parsing (no dependencies)
            String tag = extractJsonString(body, "tag_name").orElse("");
            String url = extractJsonString(body, "html_url").orElse("");

            if (tag.isBlank()) {
                return new Result(false, false, current, "", "", "Could not read tag_name from GitHub response");
            }

            String latest = normalizeVersion(tag);
            boolean updateAvailable = VersionUtil.isNewer(latest, current);

            return new Result(true, updateAvailable, current, latest, url, "");
        } catch (Exception e) {
            return new Result(false, false, current, "", "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    private static Optional<String> extractJsonString(String json, String key) {
        // looks for: "key":"value"
        // handles simple escapes poorly but fine for GitHub tag_name/html_url
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return Optional.empty();

        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return Optional.empty();

        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return Optional.empty();

        int secondQuote = json.indexOf('"', firstQuote + 1);
        while (secondQuote > 0 && json.charAt(secondQuote - 1) == '\\') {
            secondQuote = json.indexOf('"', secondQuote + 1);
        }
        if (secondQuote < 0) return Optional.empty();

        return Optional.of(json.substring(firstQuote + 1, secondQuote));
    }
}
