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
import org.bukkit.Bukkit;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class TeamNameValidator {

    private final SorekillTeamsPlugin plugin;

    // Strips any &x pair (including color/bold/reset)
    private static final Pattern ANY_AMP_CODE = Pattern.compile("(?i)&.");

    // Used to sanitize the allowed_plain config before building a regex character class
    private static final Pattern DISALLOWED_IN_CHARCLASS = Pattern.compile("[^a-zA-Z0-9_\\-\\\\]");

    public TeamNameValidator(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    public record Validation(boolean ok, String reasonKey, String coloredName, String plainName) {}

    public Validation validate(String input) {
        if (input == null) return invalid("team_name_invalid");

        String raw = input.trim();
        if (raw.isEmpty()) return invalid("team_name_invalid");

        // -------- 1) Validate formatting codes --------
        // Allow only:
        // - &0-&9, &a-&f (colors)
        // - optional &l (bold)
        // - optional &r (reset)
        // Explicitly block hex like &#RRGGBB
        boolean allowColors = plugin.getConfig().getBoolean("teams.name.allow_color_codes", true);
        boolean allowBold = plugin.getConfig().getBoolean("teams.name.allow_bold", true);
        boolean allowReset = plugin.getConfig().getBoolean("teams.name.allow_reset", true);

        if (allowColors) {
            if (raw.toLowerCase(Locale.ROOT).contains("&#")) {
                return invalid("team_name_invalid");
            }

            // validate every &<code>
            for (int i = 0; i < raw.length() - 1; i++) {
                if (raw.charAt(i) != '&') continue;

                char c = Character.toLowerCase(raw.charAt(i + 1));
                boolean ok =
                        (c >= '0' && c <= '9') ||
                        (c >= 'a' && c <= 'f') ||
                        (allowBold && c == 'l') ||
                        (allowReset && c == 'r');

                if (!ok) return invalid("team_name_invalid");
            }
        } else {
            // no formatting allowed at all
            if (raw.indexOf('&') >= 0) return invalid("team_name_invalid");
        }

        // -------- 2) Plain name checks (length + allowed characters) --------
        String plain = stripAmpCodes(raw).trim();
        if (plain.isEmpty()) return invalid("team_name_invalid");

        int min = Math.max(1, plugin.getConfig().getInt("teams.name.min_length", 3));
        int max = Math.max(min, plugin.getConfig().getInt("teams.name.max_length", 16));
        if (plain.length() < min || plain.length() > max) {
            return invalid("team_name_invalid");
        }

        Pattern plainPattern = buildAllowedPlainPattern();
        if (plainPattern != null && !plainPattern.matcher(plain).matches()) {
            return invalid("team_name_invalid");
        }

        // -------- 3) Reserved / group-name conflict checks --------
        if (isReservedOrTooClose(plain)) {
            return invalid("team_name_reserved");
        }

        // Keep raw for storage/display; caller can Msg.color(raw) when sending.
        return new Validation(true, "", raw, plain);
    }

    private Validation invalid(String key) {
        return new Validation(false, key, "", "");
    }

    private static String stripAmpCodes(String s) {
        return ANY_AMP_CODE.matcher(s == null ? "" : s).replaceAll("");
    }

    private Pattern buildAllowedPlainPattern() {
        String allowedPlain = plugin.getConfig().getString("teams.name.allowed_plain", "a-zA-Z0-9_");
        if (allowedPlain == null) allowedPlain = "a-zA-Z0-9_";
        allowedPlain = allowedPlain.trim();
        if (allowedPlain.isEmpty()) allowedPlain = "a-zA-Z0-9_";

        // Prevent regex/charclass injection from config
        // We allow typical charclass content: ranges a-z, A-Z, 0-9, underscore, dash, and backslash.
        allowedPlain = DISALLOWED_IN_CHARCLASS.matcher(allowedPlain).replaceAll("");

        try {
            return Pattern.compile("^[" + allowedPlain + "]+$");
        } catch (Exception ignored) {
            // If config is still malformed somehow, fail open to avoid breaking commands
            return null;
        }
    }

    private boolean isReservedOrTooClose(String plainName) {
        String norm = normalize(plainName);

        // Config reserved names
        if (plugin.getConfig().getBoolean("teams.reserved_names.enabled", true)) {
            List<String> reserved = plugin.getConfig().getStringList("teams.reserved_names.list");
            if (reserved != null) {
                for (String r : reserved) {
                    if (r == null) continue;
                    if (tooClose(norm, normalize(r))) return true;
                }
            }
        }

        // LuckPerms groups (optional)
        for (String groupName : LuckPermsCompat.tryGetGroupNames()) {
            if (groupName == null) continue;
            if (tooClose(norm, normalize(groupName))) return true;
        }

        return false;
    }

    private boolean tooClose(String a, String b) {
        if (a == null || b == null) return false;
        if (a.isBlank() || b.isBlank()) return false;

        if (a.equalsIgnoreCase(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;

        int maxDist = Math.max(0, plugin.getConfig().getInt("teams.reserved_match.levenshtein_distance", 2));
        if (maxDist == 0) return false;

        return levenshtein(a, b) <= maxDist;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int levenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";

        int alen = a.length();
        int blen = b.length();

        if (alen == 0) return blen;
        if (blen == 0) return alen;

        int[] prev = new int[blen + 1];
        int[] curr = new int[blen + 1];

        for (int j = 0; j <= blen; j++) prev[j] = j;

        for (int i = 1; i <= alen; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= blen; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[blen];
    }

    /**
     * Optional LuckPerms compatibility without compile-time dependency.
     * Pulls loaded group names from the LuckPerms API via Bukkit ServicesManager.
     *
     * Note: loaded groups are not guaranteed to include ALL groups unless LP has loaded them.
     * Still useful for catching obvious collisions on active servers.
     */
    private static final class LuckPermsCompat {
        private static final String LP_PLUGIN_NAME = "LuckPerms";
        private static final String LP_API_CLASS = "net.luckperms.api.LuckPerms";

        static Set<String> tryGetGroupNames() {
            try {
                if (Bukkit.getPluginManager().getPlugin(LP_PLUGIN_NAME) == null) return Set.of();

                Class<?> luckPermsClass = Class.forName(LP_API_CLASS);

                Object services = Bukkit.getServicesManager();
                Object reg = services.getClass()
                        .getMethod("getRegistration", Class.class)
                        .invoke(services, luckPermsClass);

                if (reg == null) return Set.of();

                Object provider = reg.getClass().getMethod("getProvider").invoke(reg);
                if (provider == null) return Set.of();

                Object groupManager = provider.getClass().getMethod("getGroupManager").invoke(provider);
                if (groupManager == null) return Set.of();

                Object groups = groupManager.getClass().getMethod("getLoadedGroups").invoke(groupManager);
                if (!(groups instanceof Iterable<?> it)) return Set.of();

                HashSet<String> names = new HashSet<>();
                for (Object g : it) {
                    if (g == null) continue;
                    Object nameObj = g.getClass().getMethod("getName").invoke(g);
                    if (nameObj != null) {
                        String s = nameObj.toString();
                        if (!s.isBlank()) names.add(s);
                    }
                }

                return names.isEmpty() ? Set.of() : names;
            } catch (Throwable ignored) {
                return Set.of();
            }
        }
    }
}
