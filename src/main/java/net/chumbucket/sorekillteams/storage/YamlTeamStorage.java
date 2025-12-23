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
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.UUID;

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
            plugin.getLogger().warning("Storage load skipped: unsupported TeamService type");
            return;
        }

        if (!file.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        var sec = yml.getConfigurationSection("teams");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            var tSec = sec.getConfigurationSection(key);
            if (tSec == null) continue;

            UUID id = UUID.fromString(key);
            String name = tSec.getString("name", "Team");
            UUID owner = UUID.fromString(tSec.getString("owner"));
            List<String> membersStr = tSec.getStringList("members");

            Team t = new Team(id, name, owner);
            t.getMembers().clear();
            for (String ms : membersStr) {
                t.getMembers().add(UUID.fromString(ms));
            }
            // ensure owner is included
            t.getMembers().add(owner);

            simple.putLoadedTeam(t);
        }
        plugin.getLogger().info("Loaded " + sec.getKeys(false).size() + " teams.");
    }

    @Override
    public void saveAll(TeamService service) {
        if (!(service instanceof SimpleTeamService simple)) return;

        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

        YamlConfiguration yml = new YamlConfiguration();

        for (Team t : simple.allTeams()) {
            String path = "teams." + t.getId();
            yml.set(path + ".name", t.getName());
            yml.set(path + ".owner", t.getOwner().toString());
            yml.set(path + ".members", t.getMembers().stream().map(UUID::toString).toList());
        }

        try {
            yml.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save teams.yml: " + e.getMessage());
        }
    }
}
