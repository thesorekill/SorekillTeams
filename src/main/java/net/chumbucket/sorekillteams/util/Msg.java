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
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.regex.Pattern;

public final class Msg {

    private static final String DEFAULT_PREFIX_RAW = "&8[&bTeams&8]&r ";
    private static final Pattern MULTI_WS = Pattern.compile("\\s{2,}");

    private final SorekillTeamsPlugin plugin;
    private YamlConfiguration messages;

    // Cache both raw (& codes) and colored prefix
    private String cachedPrefixRaw = DEFAULT_PREFIX_RAW;
    private String cachedPrefix = color(DEFAULT_PREFIX_RAW);

    // If true, missing keys fall back to the key string (useful for dev)
    private boolean debugMissingKeys = true;

    public Msg(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (plugin == null) return;

        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }

        File f = new File(plugin.getDataFolder(), plugin.getMessagesFileName());
        this.messages = YamlConfiguration.loadConfiguration(f);

        // Cache prefix once per reload
        String p = raw("prefix");
        if (isBlank(p)) {
            p = DEFAULT_PREFIX_RAW;
        }
        this.cachedPrefixRaw = p;
        this.cachedPrefix = color(p);

        // Optional: messages.yml toggle:
        // debug_missing_keys: false
        this.debugMissingKeys = messages != null && messages.getBoolean("debug_missing_keys", true);
    }

    public String raw(String key) {
        if (messages == null || key == null) return null;
        return messages.getString(key, null);
    }

    public boolean hasKey(String key) {
        if (messages == null || key == null) return false;
        String s = messages.getString(key, null);
        return !isBlank(s);
    }

    /** Already colored prefix, cached on reload() */
    public String prefix() {
        return cachedPrefix;
    }

    /** Raw prefix (with & codes), cached on reload() */
    public String prefixRaw() {
        return cachedPrefixRaw;
    }

    /** Formats message; missing/blank -> "" unless debug_missing_keys=true */
    public String format(String key) {
        String s = raw(key);
        if (isBlank(s)) {
            return debugMissingKeys ? (prefix() + safe(key)) : "";
        }
        return render(s, null);
    }

    /** Formats message with placeholder pairs; missing/blank -> "" unless debug_missing_keys=true */
    public String format(String key, String... pairs) {
        String s = raw(key);
        if (isBlank(s)) {
            return debugMissingKeys ? (prefix() + safe(key)) : "";
        }
        return render(s, pairs);
    }

    /**
     * Strict formatter: returns null if key missing/blank.
     * Useful when you truly want "no message" instead of a fallback.
     */
    public String formatOrNull(String key, String... pairs) {
        String s = raw(key);
        if (isBlank(s)) return null;
        return render(s, pairs);
    }

    public void send(CommandSender to, String key) {
        if (to == null) return;
        String msg = format(key);
        if (isBlank(msg)) return;
        to.sendMessage(msg);
    }

    public void send(CommandSender to, String key, String... pairs) {
        if (to == null) return;
        String msg = format(key, pairs);
        if (isBlank(msg)) return;
        to.sendMessage(msg);
    }

    /** Send a message you already built (still color-translates for convenience). */
    public void sendRaw(CommandSender to, String message) {
        if (to == null) return;
        if (isBlank(message)) return;
        to.sendMessage(color(message));
    }

    /**
     * Render a message:
     * - supports {prefix}
     * - supports placeholder pairs
     * - supports \n in YAML as real newlines
     * - colorizes once at the end
     */
    private String render(String template, String[] pairs) {
        String s = template == null ? "" : template;

        // allow "{prefix}" token in messages.yml
        s = s.replace("{prefix}", cachedPrefixRaw);

        // optional placeholder pairs
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                String from = pairs[i];
                String to = pairs[i + 1];
                if (from == null || from.isEmpty()) continue;
                s = s.replace(from, to == null ? "" : to);
            }
        }

        // normalize line breaks & whitespace a bit (keeps output clean)
        s = s.replace("\\n", "\n");
        s = MULTI_WS.matcher(s).replaceAll(" ");

        return color(s);
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
