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

    private final SorekillTeamsPlugin plugin;
    private final File file;

    public YamlTeamHomeStorage(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "team_homes.yml");
    }

    @Override
    public void loadAll(TeamHomeService homes) throws Exception {
        if (homes == null) return;

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create data folder");
        }

        if (!file.exists()) {
            homes.clearAll();
            return;
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        homes.clearAll();

        ConfigurationSection root = yml.getConfigurationSection("teams");
        if (root == null) return;

        for (String teamKey : root.getKeys(false)) {
            UUID teamId;
            try {
                teamId = UUID.fromString(teamKey);
            } catch (Exception ignored) {
                continue;
            }

            ConfigurationSection secTeam = root.getConfigurationSection(teamKey);
            if (secTeam == null) continue;

            for (String homeKey : secTeam.getKeys(false)) {
                ConfigurationSection sec = secTeam.getConfigurationSection(homeKey);
                if (sec == null) continue;

                String display = sec.getString("display", homeKey);
                String world = sec.getString("world", "");
                double x = sec.getDouble("x");
                double y = sec.getDouble("y");
                double z = sec.getDouble("z");
                float yaw = (float) sec.getDouble("yaw", 0.0);
                float pitch = (float) sec.getDouble("pitch", 0.0);
                long createdAt = sec.getLong("created_at_ms", System.currentTimeMillis());

                UUID createdBy = null;
                String createdByRaw = sec.getString("created_by", "");
                if (createdByRaw != null && !createdByRaw.isBlank()) {
                    try { createdBy = UUID.fromString(createdByRaw); } catch (Exception ignored) {}
                }

                String server = sec.getString("server", "");

                String normalized = homeKey.trim().toLowerCase(Locale.ROOT);

                TeamHome home = new TeamHome(
                        teamId,
                        normalized,
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

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create data folder");
        }

        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection root = yml.createSection("teams");

        for (TeamHome h : homes.allHomes()) {
            if (h == null || h.getTeamId() == null) continue;

            String teamKey = h.getTeamId().toString();
            String homeKey = h.getName() == null ? "" : h.getName().trim().toLowerCase(Locale.ROOT);
            if (homeKey.isBlank()) continue;

            ConfigurationSection secTeam = root.getConfigurationSection(teamKey);
            if (secTeam == null) secTeam = root.createSection(teamKey);

            ConfigurationSection sec = secTeam.createSection(homeKey);

            sec.set("display", h.getDisplayName());
            sec.set("world", h.getWorld());
            sec.set("x", h.getX());
            sec.set("y", h.getY());
            sec.set("z", h.getZ());
            sec.set("yaw", (double) h.getYaw());
            sec.set("pitch", (double) h.getPitch());
            sec.set("created_at_ms", h.getCreatedAtMs());
            sec.set("created_by", h.getCreatedBy() == null ? "" : h.getCreatedBy().toString());
            sec.set("server", h.getServerName());
        }

        yml.save(file);
    }
}
