/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.storage.sql;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.service.SimpleTeamHomeService;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.storage.TeamHomeStorage;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamHomeStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamStorage;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Objects;

public final class YamlToSqlMigrator {

    private final SorekillTeamsPlugin plugin;
    private final SqlDatabase db;

    public YamlToSqlMigrator(SorekillTeamsPlugin plugin, SqlDatabase db) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.db = Objects.requireNonNull(db, "db");
    }

    /**
     * Migrates YAML -> SQL once, only if:
     * - storage.sql.migrate_from_yaml_on_startup = true (default true)
     * - SQL tables are empty (no teams)
     * - YAML teams.yml exists and has data
     *
     * After success, YAML files are renamed to *.migrated-<timestamp>.bak
     */
    public void migrateIfNeededOnStartup() throws Exception {
        ConfigurationSection sql = plugin.getConfig().getConfigurationSection("storage.sql");
        boolean enabled = (sql == null) ? true : sql.getBoolean("migrate_from_yaml_on_startup", true);
        if (!enabled) return;

        // Only run if SQL is empty
        if (!isSqlEmpty()) return;

        File teamsYml = new File(plugin.getDataFolder(), "teams.yml");
        if (!teamsYml.exists()) return;

        plugin.getLogger().info("SQL storage is empty and teams.yml exists. Migrating YAML -> SQL...");

        // Load YAML into temporary in-memory services
        SimpleTeamService yamlTeamsService = new SimpleTeamService(plugin, new YamlTeamStorage(plugin));
        TeamStorage yamlTeamsStorage = new YamlTeamStorage(plugin);
        yamlTeamsStorage.loadAll(yamlTeamsService);

        // Save into SQL
        TeamStorage sqlTeamsStorage = new net.chumbucket.sorekillteams.storage.sql.SqlTeamStorage(db);
        sqlTeamsStorage.saveAll(yamlTeamsService);

        // Homes: migrate only if homes enabled + team_homes.yml exists
        boolean homesEnabled = plugin.getConfig().getBoolean("homes.enabled", false);
        File homesYml = new File(plugin.getDataFolder(), "team_homes.yml");

        if (homesEnabled && homesYml.exists()) {
            SimpleTeamHomeService yamlHomesService = new SimpleTeamHomeService();
            TeamHomeStorage yamlHomesStorage = new YamlTeamHomeStorage(plugin);
            yamlHomesStorage.loadAll(yamlHomesService);

            TeamHomeStorage sqlHomesStorage = new net.chumbucket.sorekillteams.storage.sql.SqlTeamHomeStorage(db);
            sqlHomesStorage.saveAll(yamlHomesService);
        }

        // Backup YAML files so we never migrate twice
        backupFile(teamsYml);
        if (homesEnabled) {
            File homes = new File(plugin.getDataFolder(), "team_homes.yml");
            if (homes.exists()) backupFile(homes);
        }

        plugin.getLogger().info("YAML -> SQL migration complete.");
    }

    private boolean isSqlEmpty() {
        String pfx = db.prefix();
        try (var c = db.getConnection();
             var ps = c.prepareStatement("SELECT COUNT(*) AS c FROM " + pfx + "teams");
             var rs = ps.executeQuery()) {
            if (!rs.next()) return true;
            return rs.getLong("c") <= 0;
        } catch (Exception e) {
            // If we can't read, treat as NOT empty to avoid destructive behavior
            plugin.getLogger().warning("Could not check SQL emptiness; skipping migration: " +
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private void backupFile(File f) throws Exception {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        File out = new File(f.getParentFile(), f.getName() + ".migrated-" + ts + ".bak");
        Files.move(f.toPath(), out.toPath());
        plugin.getLogger().info("Backed up " + f.getName() + " -> " + out.getName());
    }
}
