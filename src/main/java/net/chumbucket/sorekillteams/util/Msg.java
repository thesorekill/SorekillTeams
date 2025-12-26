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

public final class Msg {

    private final SorekillTeamsPlugin plugin;
    private YamlConfiguration messages;

    // Cache both raw (& codes) and colored prefix
    private String cachedPrefixRaw = "&8[&bTeams&8]&r ";
    private String cachedPrefix = ChatColor.translateAlternateColorCodes('&', cachedPrefixRaw);

    // If true, missing keys fall back to the key string (dev only)
    private boolean debugMissingKeys = false;

    public Msg(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }

        File f = new File(plugin.getDataFolder(), plugin.getMessagesFileName());
        this.messages = YamlConfiguration.loadConfiguration(f);

        // Cache prefix once per reload
        String p = raw("prefix");
        if (p == null || p.isBlank()) {
            p = "&8[&bTeams&8]&r ";
        }
        this.cachedPrefixRaw = p;
        this.cachedPrefix = color(p);

        // dev toggle
        this.debugMissingKeys = messages != null && messages.getBoolean("debug_missing_keys", false);
    }

    public String raw(String key) {
        if (messages == null || key == null) return null;
        return messages.getString(key, null);
    }

    public boolean hasKey(String key) {
        if (messages == null || key == null) return false;
        return messages.contains(key);
    }

    /** Already colored prefix, cached on reload() */
    public String prefix() {
        return cachedPrefix;
    }

    /**
     * Formats message. Rules:
     * - missing key -> null (or prefix+key if debugMissingKeys=true)
     * - blank string ("") -> null (DISABLED)
     */
    public String format(String key) {
        String s = raw(key);

        // missing
        if (s == null) {
            return debugMissingKeys ? (prefix() + key) : null;
        }

        // explicitly disabled
        if (s.isBlank()) {
            return null;
        }

        s = s.replace("{prefix}", cachedPrefixRaw);
        return color(s);
    }

    /**
     * Formats message with placeholder pairs.
     * - missing key -> null (or prefix+key if debugMissingKeys=true)
     * - blank string ("") -> null (DISABLED)
     */
    public String format(String key, String... pairs) {
        String s = raw(key);

        // missing
        if (s == null) {
            return debugMissingKeys ? (prefix() + key) : null;
        }

        // explicitly disabled
        if (s.isBlank()) {
            return null;
        }

        s = s.replace("{prefix}", cachedPrefixRaw);

        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                String from = pairs[i] == null ? "" : pairs[i];
                String to = pairs[i + 1] == null ? "" : pairs[i + 1];
                s = s.replace(from, to);
            }
        }

        return color(s);
    }

    public void send(CommandSender to, String key) {
        if (to == null) return;
        String msg = format(key);
        if (msg == null) return; // disabled or missing
        to.sendMessage(msg);
    }

    public void send(CommandSender to, String key, String... pairs) {
        if (to == null) return;
        String msg = format(key, pairs);
        if (msg == null) return; // disabled or missing
        to.sendMessage(msg);
    }

    /** Send a message you already built (still color-translates for convenience). */
    public void sendRaw(CommandSender to, String message) {
        if (to == null) return;
        if (message == null || message.isBlank()) return;
        to.sendMessage(color(message));
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
