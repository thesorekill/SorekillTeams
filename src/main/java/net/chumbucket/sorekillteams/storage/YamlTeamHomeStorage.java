/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.storage;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.TeamHome;
import net.chumbucket.sorekillteams.service.TeamHomeService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;
import java.util.UUID;

public final class YamlTeamHomeStorage implements TeamHomeStorage {

    private static final String ROOT_TEAMS = "teams";

    private static final String KEY_DISPLAY = "display";
    private static final String KEY_WORLD = "world";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_YAW = "yaw";
    private static final String KEY_PITCH = "pitch";
    private static final String KEY_CREATED_AT = "created_at_ms";
    private static final String KEY_CREATED_BY = "created_by";
    private static final String KEY_SERVER = "server";

    private final SorekillTeamsPlugin plugin;
    private final File file;

    public YamlTeamHomeStorage(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "team_homes.yml");
    }

    @Override
    public void loadAll(TeamHomeService homes) throws Exception {
        if (homes == null) return;

        ensureDataFolder();

        homes.clearAll();

        if (!file.exists()) {
            return;
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection(ROOT_TEAMS);
        if (root == null) return;

        for (String teamKey : root.getKeys(false)) {
            UUID teamId = parseUuid(teamKey);
            if (teamId == null) continue;

            ConfigurationSection secTeam = root.getConfigurationSection(teamKey);
            if (secTeam == null) continue;

            for (String homeKeyRaw : secTeam.getKeys(false)) {
                ConfigurationSection sec = secTeam.getConfigurationSection(homeKeyRaw);
                if (sec == null) continue;

                String homeKey = normalizeKey(homeKeyRaw);
                if (homeKey.isBlank()) continue;

                String display = sec.getString(KEY_DISPLAY, homeKeyRaw);
                String world = sec.getString(KEY_WORLD, "");
                double x = sec.getDouble(KEY_X);
                double y = sec.getDouble(KEY_Y);
                double z = sec.getDouble(KEY_Z);
                float yaw = (float) sec.getDouble(KEY_YAW, 0.0);
                float pitch = (float) sec.getDouble(KEY_PITCH, 0.0);
                long createdAt = sec.getLong(KEY_CREATED_AT, System.currentTimeMillis());

                UUID createdBy = parseUuid(sec.getString(KEY_CREATED_BY, ""));
                String server = sec.getString(KEY_SERVER, "");

                TeamHome home = new TeamHome(
                        teamId,
                        homeKey,
                        display,
                        world,
                        x, y, z,
                        yaw, pitch,
                        createdAt,
                        createdBy,
                        server
                );

                homes.putLoadedHome(home);
            }
        }
    }

    @Override
    public void saveAll(TeamHomeService homes) throws Exception {
        if (homes == null) return;

        ensureDataFolder();

        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection root = yml.createSection(ROOT_TEAMS);

        for (TeamHome h : homes.allHomes()) {
            if (h == null || h.getTeamId() == null) continue;

            String teamKey = h.getTeamId().toString();
            String homeKey = normalizeKey(h.getName());
            if (homeKey.isBlank()) continue;

            ConfigurationSection secTeam = root.getConfigurationSection(teamKey);
            if (secTeam == null) secTeam = root.createSection(teamKey);

            // Create (or replace) the home section deterministically
            ConfigurationSection sec = secTeam.createSection(homeKey);

            sec.set(KEY_DISPLAY, h.getDisplayName());
            sec.set(KEY_WORLD, h.getWorld());
            sec.set(KEY_X, h.getX());
            sec.set(KEY_Y, h.getY());
            sec.set(KEY_Z, h.getZ());
            sec.set(KEY_YAW, (double) h.getYaw());
            sec.set(KEY_PITCH, (double) h.getPitch());
            sec.set(KEY_CREATED_AT, h.getCreatedAtMs());
            sec.set(KEY_CREATED_BY, h.getCreatedBy() == null ? "" : h.getCreatedBy().toString());
            sec.set(KEY_SERVER, h.getServerName());
        }

        yml.save(file);
    }

    private void ensureDataFolder() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Could not create data folder: " + folder.getAbsolutePath());
        }
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
