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
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

public final class YamlTeamStorage implements TeamStorage {

    private final SorekillTeamsPlugin plugin;
    private final File file;

    public YamlTeamStorage(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
    }

    @Override
    public void loadAll(TeamService service) {
        if (!(service instanceof SimpleTeamService simple)) {
            plugin.getLogger().warning("Storage load skipped: unsupported TeamService type (" +
                    (service == null ? "null" : service.getClass().getName()) + ")");
            return;
        }

        if (!file.exists()) {
            plugin.getLogger().info("No teams.yml found; starting fresh.");
            return;
        }

        final YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        final ConfigurationSection sec = yml.getConfigurationSection("teams");
        if (sec == null) {
            plugin.getLogger().info("teams.yml has no 'teams' section; starting fresh.");
            return;
        }

        int loaded = 0;
        int skipped = 0;

        for (String key : sec.getKeys(false)) {
            final ConfigurationSection tSec = sec.getConfigurationSection(key);
            if (tSec == null) {
                skipped++;
                continue;
            }

            try {
                final UUID id = safeUuid(key);
                if (id == null) {
                    plugin.getLogger().warning("Skipping team with invalid UUID key: " + key);
                    skipped++;
                    continue;
                }

                final String nameRaw = tSec.getString("name", "Team");
                final String name = (nameRaw == null || nameRaw.isBlank()) ? "Team" : nameRaw.trim();

                final UUID owner = safeUuid(tSec.getString("owner", null));
                if (owner == null) {
                    plugin.getLogger().warning("Skipping team " + id + " ('" + name + "') due to missing/invalid owner UUID");
                    skipped++;
                    continue;
                }

                final List<String> membersStr = tSec.getStringList("members");
                final Set<UUID> members = new LinkedHashSet<>();
                for (String ms : membersStr) {
                    UUID m = safeUuid(ms);
                    if (m != null) members.add(m);
                }

                // Ensure owner is included
                members.add(owner);

                // ✅ created date (fallback: "now" if missing)
                long createdAt = tSec.getLong("created_at", System.currentTimeMillis());

                final Team t = new Team(id, name, owner, createdAt);
                t.getMembers().clear();
                t.getMembers().addAll(members);

                // ✅ team FF toggle
                t.setFriendlyFireEnabled(tSec.getBoolean("friendly_fire", false));

                simple.putLoadedTeam(t);
                loaded++;
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping malformed team entry '" + key + "': " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                skipped++;
            }
        }

        plugin.getLogger().info("Loaded " + loaded + " teams. Skipped " + skipped + " malformed entries.");
    }

    @Override
    public void saveAll(TeamService service) {
        if (!(service instanceof SimpleTeamService simple)) {
            plugin.getLogger().warning("Storage save skipped: unsupported TeamService type (" +
                    (service == null ? "null" : service.getClass().getName()) + ")");
            return;
        }

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().severe("Failed to create plugin data folder: " + plugin.getDataFolder().getAbsolutePath());
            return;
        }

        final YamlConfiguration yml = new YamlConfiguration();

        // deterministic ordering
        final List<Team> teams = new ArrayList<>(simple.allTeams());
        teams.sort(Comparator.comparing(t -> t.getId().toString()));

        for (Team t : teams) {
            final String path = "teams." + t.getId();
            yml.set(path + ".name", t.getName());
            yml.set(path + ".owner", t.getOwner().toString());

            // Ensure members list is unique + includes owner
            final LinkedHashSet<UUID> members = new LinkedHashSet<>(t.getMembers());
            members.add(t.getOwner());
            yml.set(path + ".members", members.stream().map(UUID::toString).collect(Collectors.toList()));

            // ✅ 1.0.5 fields
            yml.set(path + ".friendly_fire", t.isFriendlyFireEnabled());
            yml.set(path + ".created_at", t.getCreatedAtMs());
        }

        final File tmp = new File(plugin.getDataFolder(), "teams.yml.tmp");

        try {
            yml.save(tmp);

            Path tmpPath = tmp.toPath();
            Path destPath = file.toPath();

            try {
                Files.move(tmpPath, destPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmpPath, destPath, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save teams.yml: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private UUID safeUuid(String s) {
        if (s == null) return null;
        final String v = s.trim();
        if (v.isEmpty()) return null;
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}