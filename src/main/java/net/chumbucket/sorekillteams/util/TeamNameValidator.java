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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class TeamNameValidator {

    private final SorekillTeamsPlugin plugin;

    // Safety: strip any &X style codes when making a plain name
    private static final Pattern ANY_AMP_CODE = Pattern.compile("(?i)&.");

    public TeamNameValidator(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    public record Validation(boolean ok, String reasonKey, String coloredName, String plainName) {}

    public Validation validate(String input) {
        if (input == null) return new Validation(false, "team_name_invalid", "", "");

        String raw = input.trim();

        // -------- 1) Validate formatting codes (ONLY &0-&f colors + optional &l bold + optional &r reset) --------
        boolean allowColors = plugin.getConfig().getBoolean("teams.name.allow_color_codes", true);
        boolean allowBold = plugin.getConfig().getBoolean("teams.name.allow_bold", true);
        boolean allowReset = plugin.getConfig().getBoolean("teams.name.allow_reset", true);

        if (allowColors) {
            // Block hex style (&#RRGGBB) explicitly
            if (raw.toLowerCase(Locale.ROOT).contains("&#")) {
                return new Validation(false, "team_name_invalid", "", "");
            }

            // Walk all &<code> usages; reject anything not allowed (blocks &o italics, &k magic, &m, &n, etc.)
            for (int i = 0; i < raw.length() - 1; i++) {
                if (raw.charAt(i) != '&') continue;

                char c = Character.toLowerCase(raw.charAt(i + 1));
                boolean ok =
                        (c >= '0' && c <= '9') ||
                        (c >= 'a' && c <= 'f') ||
                        (allowBold && c == 'l') ||
                        (allowReset && c == 'r');

                if (!ok) {
                    return new Validation(false, "team_name_invalid", "", "");
                }
            }
        } else {
            // No formatting allowed at all
            if (raw.contains("&")) {
                return new Validation(false, "team_name_invalid", "", "");
            }
        }

        // -------- 2) Plain name checks (length + allowed characters) --------
        String plain = stripAmpCodes(raw).trim();

        int min = plugin.getConfig().getInt("teams.name.min_length", 3);
        int max = plugin.getConfig().getInt("teams.name.max_length", 16);
        if (plain.length() < min || plain.length() > max) {
            return new Validation(false, "team_name_invalid", "", "");
        }

        String allowedPlain = plugin.getConfig().getString("teams.name.allowed_plain", "a-zA-Z0-9_");
        Pattern plainPattern = Pattern.compile("^[" + allowedPlain + "]+$");
        if (!plainPattern.matcher(plain).matches()) {
            return new Validation(false, "team_name_invalid", "", "");
        }

        // -------- 3) Reserved / group-name conflict checks --------
        if (isReservedOrTooClose(plain)) {
            return new Validation(false, "team_name_reserved", "", "");
        }

        // Keep the original raw for storage/display; callers can Msg.color(raw) when sending to players.
        return new Validation(true, "", raw, plain);
    }

    private static String stripAmpCodes(String s) {
        // strips any &x pairs (including color/bold/reset)
        return ANY_AMP_CODE.matcher(s).replaceAll("");
    }

    private boolean isReservedOrTooClose(String plainName) {
        String norm = normalize(plainName);

        // Config reserved names
        if (plugin.getConfig().getBoolean("teams.reserved_names.enabled", true)) {
            for (String r : plugin.getConfig().getStringList("teams.reserved_names.list")) {
                if (tooClose(norm, normalize(r))) return true;
            }
        }

        // LuckPerms groups (optional) via reflection (no compile-time dependency)
        for (String groupName : LuckPermsCompat.tryGetGroupNames()) {
            if (tooClose(norm, normalize(groupName))) return true;
        }

        return false;
    }

    private boolean tooClose(String a, String b) {
        if (a.isBlank() || b.isBlank()) return false;

        if (a.equalsIgnoreCase(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;

        int maxDist = plugin.getConfig().getInt("teams.reserved_match.levenshtein_distance", 2);
        return levenshtein(a, b) <= maxDist;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Optional LuckPerms compatibility without compile-time dependency.
     * Pulls loaded group names from the LuckPerms API via Bukkit ServicesManager.
     */
    private static final class LuckPermsCompat {
        private static final String LP_PLUGIN_NAME = "LuckPerms";
        private static final String LP_API_CLASS = "net.luckperms.api.LuckPerms";

        static Set<String> tryGetGroupNames() {
            try {
                if (Bukkit.getPluginManager().getPlugin(LP_PLUGIN_NAME) == null) return Set.of();

                Class<?> luckPermsClass = Class.forName(LP_API_CLASS);

                // ServicesManager#getRegistration(Class)
                Object reg = Bukkit.getServicesManager()
                        .getClass()
                        .getMethod("getRegistration", Class.class)
                        .invoke(Bukkit.getServicesManager(), luckPermsClass);

                if (reg == null) return Set.of();

                Object provider = reg.getClass().getMethod("getProvider").invoke(reg);
                if (provider == null) return Set.of();

                // provider.getGroupManager()
                Object groupManager = provider.getClass().getMethod("getGroupManager").invoke(provider);
                if (groupManager == null) return Set.of();

                // groupManager.getLoadedGroups()
                Object groups = groupManager.getClass().getMethod("getLoadedGroups").invoke(groupManager);
                if (!(groups instanceof Iterable<?> it)) return Set.of();

                java.util.HashSet<String> names = new java.util.HashSet<>();
                for (Object g : it) {
                    if (g == null) continue;
                    Object nameObj = g.getClass().getMethod("getName").invoke(g);
                    if (nameObj != null) names.add(nameObj.toString());
                }
                return names;
            } catch (Throwable ignored) {
                return Set.of();
            }
        }
    }
}