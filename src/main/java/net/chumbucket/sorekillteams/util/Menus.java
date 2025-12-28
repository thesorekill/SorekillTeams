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
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class Menus {

    private final SorekillTeamsPlugin plugin;
    private YamlConfiguration yaml;

    public Menus(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        File f = new File(plugin.getDataFolder(), plugin.getMenusFileName());
        this.yaml = YamlConfiguration.loadConfiguration(f);
    }

    public boolean enabledInConfigYml() {
        // master toggle lives in config.yml
        return plugin.getConfig().getBoolean("menus.enabled", true);
    }

    /** Primary GUI entry point */
    public void open(Player player, String menuKey) {
        if (player == null || menuKey == null || menuKey.isBlank()) return;
        if (!enabledInConfigYml()) return;
        if (plugin.menuRouter() == null) return;

        plugin.menuRouter().open(player, menuKey);
    }

    public ConfigurationSection menu(String key) {
        if (yaml == null || key == null || key.isBlank()) return null;
        return yaml.getConfigurationSection("menus." + key);
    }

    public String str(ConfigurationSection sec, String path, String def) {
        if (sec == null) return def;
        String v = sec.getString(path, def);
        if (v == null || v.isBlank()) return def;
        return v;
    }

    public int integer(ConfigurationSection sec, String path, int def) {
        return sec == null ? def : sec.getInt(path, def);
    }

    public boolean bool(ConfigurationSection sec, String path, boolean def) {
        return sec == null ? def : sec.getBoolean(path, def);
    }

    public List<String> strList(ConfigurationSection sec, String path) {
        if (sec == null) return Collections.emptyList();
        List<String> list = sec.getStringList(path);
        return list == null ? Collections.emptyList() : list;
    }

    public Material material(ConfigurationSection sec, String path, Material def) {
        String raw = str(sec, path, def == null ? "STONE" : def.name());
        Material m = Material.matchMaterial(raw);
        return m == null ? (def == null ? Material.STONE : def) : m;
    }

    public static int clampRows(int rows) {
        if (rows < 1) return 1;
        if (rows > 6) return 6;
        return rows;
    }

    public static int clampSlot(int slot, int size) {
        if (slot < 0) return 0;
        if (slot >= size) return size - 1;
        return slot;
    }
}
