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

    public Msg(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), plugin.getMessagesFileName());
        this.messages = YamlConfiguration.loadConfiguration(f);
    }

    public String raw(String key) {
        return messages.getString(key, "");
    }

    public String prefix() {
        return color(raw("prefix"));
    }

    public String format(String key) {
        String s = raw(key);
        if (s == null) s = "";
        s = s.replace("{prefix}", prefix());
        return color(s);
    }

    public String format(String key, String... pairs) {
        String s = format(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace(pairs[i], pairs[i + 1]);
        }
        return s;
    }

    public void send(CommandSender to, String key) {
        to.sendMessage(format(key));
    }

    public void send(CommandSender to, String key, String... pairs) {
        to.sendMessage(format(key, pairs));
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
