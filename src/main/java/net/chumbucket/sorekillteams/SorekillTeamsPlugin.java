/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams;

import net.chumbucket.sorekillteams.command.AdminCommand;
import net.chumbucket.sorekillteams.command.TeamChatCommand;
import net.chumbucket.sorekillteams.command.TeamCommand;
import net.chumbucket.sorekillteams.listener.FriendlyFireListener;
import net.chumbucket.sorekillteams.listener.TeamChatListener;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamService;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamStorage;
import net.chumbucket.sorekillteams.update.UpdateChecker;
import net.chumbucket.sorekillteams.update.UpdateNotifyListener;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SorekillTeamsPlugin extends JavaPlugin {

    private Msg msg;
    private TeamStorage storage;
    private TeamService teams;

    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing(getMessagesFileName());

        this.msg = new Msg(this);
        this.storage = new YamlTeamStorage(this);
        this.teams = new SimpleTeamService(this, storage);

        storage.loadAll(teams);

        // commands
        if (getCommand("sorekillteams") != null) getCommand("sorekillteams").setExecutor(new AdminCommand(this));
        if (getCommand("team") != null) getCommand("team").setExecutor(new TeamCommand(this));
        if (getCommand("tc") != null) getCommand("tc").setExecutor(new TeamChatCommand(this));

        // listeners
        getServer().getPluginManager().registerEvents(new FriendlyFireListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamChatListener(this), this);

        // update checker (v1.0.1)
        this.updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(new UpdateNotifyListener(this, updateChecker), this);
        updateChecker.checkNowAsync();

        getLogger().info("SorekillTeams enabled.");
    }

    @Override
    public void onDisable() {
        try {
            if (storage != null && teams != null) {
                storage.saveAll(teams);
            }
        } catch (Exception e) {
            getLogger().severe("Failed to save teams: " + e.getMessage());
        }
        getLogger().info("SorekillTeams disabled.");
    }

    public void reloadEverything() {
        reloadConfig();
        saveResourceIfMissing(getMessagesFileName());

        if (msg != null) msg.reload();

        // re-check updates after reload (if configured)
        if (updateChecker != null) updateChecker.checkNowAsync();
    }

    private void saveResourceIfMissing(String resourceName) {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File file = new File(getDataFolder(), resourceName);
        if (!file.exists()) saveResource(resourceName, false);
    }

    public String getMessagesFileName() {
        return getConfig().getString("files.messages", "messages.yml");
    }

    public Msg msg() { return msg; }
    public TeamService teams() { return teams; }
    public TeamStorage storage() { return storage; }
    public UpdateChecker updateChecker() { return updateChecker; }
}
