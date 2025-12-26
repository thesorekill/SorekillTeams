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
import net.chumbucket.sorekillteams.command.TeamCommandTabCompleter;
import net.chumbucket.sorekillteams.listener.CreateTeamFlowListener;
import net.chumbucket.sorekillteams.listener.FriendlyFireListener;
import net.chumbucket.sorekillteams.listener.MainMenuListener;
import net.chumbucket.sorekillteams.listener.MenuCloseListener;
import net.chumbucket.sorekillteams.listener.TeamChatListener;
import net.chumbucket.sorekillteams.listener.TeamOnlineStatusListener;
import net.chumbucket.sorekillteams.menu.MenuRouter;
import net.chumbucket.sorekillteams.model.TeamInvites;
import net.chumbucket.sorekillteams.service.SimpleTeamHomeService;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamHomeService;
import net.chumbucket.sorekillteams.service.TeamService;
import net.chumbucket.sorekillteams.storage.TeamHomeStorage;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamHomeStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamStorage;
import net.chumbucket.sorekillteams.update.UpdateChecker;
import net.chumbucket.sorekillteams.update.UpdateNotifyListener;
import net.chumbucket.sorekillteams.util.Actionbar;
import net.chumbucket.sorekillteams.util.Debug;
import net.chumbucket.sorekillteams.util.Menus;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SorekillTeamsPlugin extends JavaPlugin {

    private Msg msg;
    private Menus menus; // menus.yml loader
    private Actionbar actionbar;
    private Debug debug;

    // ✅ NEW: GUI router/executor
    private MenuRouter menuRouter;

    private TeamStorage storage;
    private TeamService teams;

    // Team homes
    private TeamHomeStorage teamHomeStorage;
    private TeamHomeService teamHomes;

    // Invite store (in-memory)
    private final TeamInvites invites = new TeamInvites();

    private UpdateChecker updateChecker;
    private UpdateNotifyListener updateNotifyListener;

    private int invitePurgeTaskId = -1;
    private int autosaveTaskId = -1;

    // Prevent overlapping saves
    private final AtomicBoolean saveInFlight = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ensure resources exist
        saveResourceIfMissing(getMessagesFileNameSafe());
        saveResourceIfMissing(getMenusFileNameSafe());

        this.msg = new Msg(this);
        this.menus = new Menus(this);
        this.actionbar = new Actionbar(this);
        this.debug = new Debug(this);

        // ✅ NEW
        this.menuRouter = new MenuRouter(this);

        this.storage = new YamlTeamStorage(this);
        this.teams = new SimpleTeamService(this, storage);

        // Homes (team)
        syncHomesWiringFromConfig(true);

        // Load teams (fail-safe)
        try {
            storage.loadAll(teams);
        } catch (Exception e) {
            getLogger().severe("Failed to load teams from storage. Disabling plugin to prevent data loss.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Load homes (best-effort)
        loadHomesBestEffort("startup");

        // Commands
        registerCommand("sorekillteams", new AdminCommand(this));
        registerCommand("team", new TeamCommand(this));
        registerCommand("tc", new TeamChatCommand(this)); // /tc

        // Tab completion
        registerTabCompleter("team", new TeamCommandTabCompleter(this));

        // Listeners (core)
        getServer().getPluginManager().registerEvents(new FriendlyFireListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamChatListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamOnlineStatusListener(this), this);

        // ✅ Menu listeners
        getServer().getPluginManager().registerEvents(new MainMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new CreateTeamFlowListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuCloseListener(this), this);

        startInvitePurgeTask();
        startAutosaveTask();

        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams enabled.");
    }

    @Override
    public void onDisable() {
        stopTask(invitePurgeTaskId);
        invitePurgeTaskId = -1;

        stopTask(autosaveTaskId);
        autosaveTaskId = -1;

        // best-effort final save
        trySaveNowSync("shutdown");

        getLogger().info("SorekillTeams disabled.");
    }

    public void reloadEverything() {
        reloadConfig();

        // ensure resources exist (in case user deleted)
        saveResourceIfMissing(getMessagesFileNameSafe());
        saveResourceIfMissing(getMenusFileNameSafe());

        if (msg != null) {
            try {
                msg.reload();
            } catch (Exception e) {
                getLogger().warning("Failed to reload messages: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        if (menus != null) {
            try {
                menus.reload();
            } catch (Exception e) {
                getLogger().warning("Failed to reload menus.yml: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } else {
            menus = new Menus(this);
        }

        // ✅ ensure router exists after reload
        if (menuRouter == null) menuRouter = new MenuRouter(this);

        // Reload teams (best-effort; keep old if reload fails)
        try {
            if (storage == null) storage = new YamlTeamStorage(this);

            TeamService fresh = new SimpleTeamService(this, storage);
            storage.loadAll(fresh);
            this.teams = fresh;

            invites.purgeExpiredAll(System.currentTimeMillis());
        } catch (Exception e) {
            getLogger().severe("Reload failed while loading teams. Keeping previous in-memory teams.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Reload homes enabled/disabled + load file
        syncHomesWiringFromConfig(false);
        loadHomesBestEffort("reload");

        startInvitePurgeTask();
        startAutosaveTask();

        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams reloaded.");
    }

    /**
     * Create/clear home services based on config.
     * @param isStartup if true, we wire without worrying about old state
     */
    private void syncHomesWiringFromConfig(boolean isStartup) {
        boolean homesEnabled = getConfig().getBoolean("homes.enabled", false);

        if (!homesEnabled) {
            teamHomeStorage = null;
            teamHomes = null;
            return;
        }

        // storage
        if (teamHomeStorage == null) {
            teamHomeStorage = new YamlTeamHomeStorage(this);
        }

        // service
        if (teamHomes == null || !isStartup) {
            teamHomes = buildTeamHomeService();
        }
    }

    private TeamHomeService buildTeamHomeService() {
        return new SimpleTeamHomeService();
    }

    private void loadHomesBestEffort(String phase) {
        if (teamHomeStorage == null || teamHomes == null) return;

        try {
            teamHomeStorage.loadAll(teamHomes);
        } catch (Exception e) {
            getLogger().warning("Failed to load team_homes.yml (" + phase + "): " +
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void registerCommand(String name, CommandExecutor executor) {
        final PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '/" + name + "' not registered (missing from plugin.yml?).");
            return;
        }
        cmd.setExecutor(executor);
    }

    private void registerTabCompleter(String name, org.bukkit.command.TabCompleter completer) {
        final PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("TabCompleter for '/" + name + "' not registered (missing from plugin.yml?).");
            return;
        }
        cmd.setTabCompleter(completer);
    }

    private void saveResourceIfMissing(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) return;

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder: " + getDataFolder().getAbsolutePath());
            return;
        }

        File file = new File(getDataFolder(), resourceName);
        if (!file.exists()) {
            try {
                saveResource(resourceName, false);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Resource '" + resourceName + "' not found in jar; skipping file copy.");
            }
        }
    }

    private String getMessagesFileNameSafe() {
        String v = null;
        try {
            v = getConfig().getString("files.messages", "messages.yml");
        } catch (Exception ignored) {}
        if (v == null || v.isBlank()) return "messages.yml";
        return v.trim();
    }

    private String getMenusFileNameSafe() {
        String v = null;
        try {
            v = getConfig().getString("files.menus", "menus.yml");
        } catch (Exception ignored) {}
        if (v == null || v.isBlank()) return "menus.yml";
        return v.trim();
    }

    private void startInvitePurgeTask() {
        stopTask(invitePurgeTaskId);

        int seconds = Math.max(10, getConfig().getInt("invites.purge_seconds", 60));
        long ticks = seconds * 20L;

        invitePurgeTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(
                this,
                () -> invites.purgeExpiredAll(System.currentTimeMillis()),
                ticks,
                ticks
        );
    }

    private void startAutosaveTask() {
        stopTask(autosaveTaskId);

        int seconds = Math.max(0, getConfig().getInt("storage.autosave_seconds", 60));
        if (seconds <= 0) {
            autosaveTaskId = -1;
            return;
        }

        long ticks = seconds * 20L;

        autosaveTaskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> trySaveNowAsync("autosave"),
                ticks,
                ticks
        ).getTaskId();
    }

    private void trySaveNowAsync(String reason) {
        if (!saveInFlight.compareAndSet(false, true)) return;

        try {
            TeamStorage s = this.storage;
            TeamService t = this.teams;
            if (s != null && t != null) {
                s.saveAll(t);
            }

            TeamHomeStorage hs = this.teamHomeStorage;
            TeamHomeService hv = this.teamHomes;
            if (hs != null && hv != null) {
                hs.saveAll(hv);
            }
        } catch (Exception e) {
            getLogger().severe("Save failed (" + reason + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            saveInFlight.set(false);
        }
    }

    private void trySaveNowSync(String reason) {
        if (!saveInFlight.compareAndSet(false, true)) return;

        try {
            TeamStorage s = this.storage;
            TeamService t = this.teams;
            if (s != null && t != null) {
                s.saveAll(t);
            }

            TeamHomeStorage hs = this.teamHomeStorage;
            TeamHomeService hv = this.teamHomes;
            if (hs != null && hv != null) {
                hs.saveAll(hv);
            }
        } catch (Exception e) {
            getLogger().severe("Save failed (" + reason + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            saveInFlight.set(false);
        }
    }

    private void stopTask(int taskId) {
        if (taskId < 0) return;
        try {
            getServer().getScheduler().cancelTask(taskId);
        } catch (Exception ignored) {}
    }

    private void syncUpdateCheckerWithConfig() {
        boolean enabled = getConfig().getBoolean("update_checker.enabled", true);

        if (this.updateChecker == null) {
            this.updateChecker = new UpdateChecker(this);
        }

        if (this.updateNotifyListener == null) {
            this.updateNotifyListener = new UpdateNotifyListener(this, updateChecker);
            getServer().getPluginManager().registerEvents(updateNotifyListener, this);
        }

        if (enabled) {
            updateChecker.checkNowAsync();
        } else {
            getLogger().info("UpdateChecker disabled in config.");
        }
    }

    // =========================
    // Exposed helpers
    // =========================

    public String getMessagesFileName() { return getMessagesFileNameSafe(); }
    public String getMenusFileName() { return getMenusFileNameSafe(); }

    public Msg msg() { return msg; }
    public Menus menus() { return menus; }
    public Actionbar actionbar() { return actionbar; }
    public Debug debug() { return debug; }

    // ✅ NEW
    public MenuRouter menuRouter() { return menuRouter; }

    public TeamService teams() { return teams; }
    public TeamStorage storage() { return storage; }

    public TeamInvites invites() { return invites; }

    public UpdateChecker updateChecker() { return updateChecker; }

    // Team homes
    public TeamHomeService teamHomes() { return teamHomes; }
    public TeamHomeStorage teamHomeStorage() { return teamHomeStorage; }

    // ----------------------------------------------------------------------------
    // Friendly Fire config helpers
    // ----------------------------------------------------------------------------
    public boolean isFriendlyFireToggleEnabled() {
        return getConfig().getBoolean("friendly_fire.toggle_enabled", true);
    }
}
