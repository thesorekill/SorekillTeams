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
import net.chumbucket.sorekillteams.listener.FriendlyFireListener;
import net.chumbucket.sorekillteams.listener.TeamChatListener;
import net.chumbucket.sorekillteams.model.TeamInvites;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamService;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamStorage;
import net.chumbucket.sorekillteams.update.UpdateChecker;
import net.chumbucket.sorekillteams.update.UpdateNotifyListener;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SorekillTeamsPlugin extends JavaPlugin {

    private Msg msg;
    private TeamStorage storage;
    private TeamService teams;

    // 1.0.3: invite store (in-memory). Used by services/commands.
    private final TeamInvites invites = new TeamInvites();

    private UpdateChecker updateChecker;
    private UpdateNotifyListener updateNotifyListener;

    private int invitePurgeTaskId = -1;
    private int autosaveTaskId = -1;

    // ✅ 1.0.6: prevent overlapping saves (autosave + command-triggered saves + disable)
    private final AtomicBoolean saveInFlight = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        // Config + message file
        saveDefaultConfig();
        saveResourceIfMissing(getMessagesFileNameSafe());

        // Core services
        this.msg = new Msg(this);
        this.storage = new YamlTeamStorage(this);
        this.teams = new SimpleTeamService(this, storage);

        // Load data (fail-safe)
        try {
            storage.loadAll(teams);
        } catch (Exception e) {
            getLogger().severe("Failed to load teams from storage. Disabling plugin to prevent data loss.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Commands
        registerCommand("sorekillteams", new AdminCommand(this));
        registerCommand("team", new TeamCommand(this));
        registerCommand("tc", new TeamChatCommand(this));

        // ✅ 1.0.7: Tab completion for /team
        registerTabCompleter("team", new TeamCommandTabCompleter(this));

        // Listeners
        getServer().getPluginManager().registerEvents(new FriendlyFireListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamChatListener(this), this);

        // Housekeeping tasks
        startInvitePurgeTask();
        startAutosaveTask();

        // Update checker (config togglable)
        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams enabled.");
    }

    @Override
    public void onDisable() {
        // Cancel tasks (Bukkit also cancels on disable, but this keeps ids clean)
        stopTask(invitePurgeTaskId);
        invitePurgeTaskId = -1;

        stopTask(autosaveTaskId);
        autosaveTaskId = -1;

        // ✅ 1.0.6: final save (sync) with overlap guard
        trySaveNowSync("shutdown");

        getLogger().info("SorekillTeams disabled.");
    }

    /**
     * Reloads config + messages + storage-backed data.
     * Patch-safe behavior: rehydrate the TeamService from disk.
     */
    public void reloadEverything() {
        reloadConfig();
        saveResourceIfMissing(getMessagesFileNameSafe());

        if (msg != null) {
            try {
                msg.reload();
            } catch (Exception e) {
                getLogger().warning("Failed to reload messages: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // Reload teams from storage (avoid stale in-memory state)
        try {
            if (storage == null) storage = new YamlTeamStorage(this);

            // Recreate service to ensure a clean state (simple + reliable for patch releases)
            this.teams = new SimpleTeamService(this, storage);
            storage.loadAll(teams);

            // Clean expired invites immediately after reload
            invites.purgeExpiredAll(System.currentTimeMillis());
        } catch (Exception e) {
            getLogger().severe("Reload failed while loading teams. Current in-memory state may be incomplete.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Restart configurable tasks (in case user changed values)
        startInvitePurgeTask();
        startAutosaveTask();

        // Apply updates toggle on reload too
        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams reloaded.");
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

    /**
     * Safe wrapper that never returns blank, and tolerates config weirdness.
     */
    private String getMessagesFileNameSafe() {
        String v = null;
        try {
            v = getConfig().getString("files.messages", "messages.yml");
        } catch (Exception ignored) {}
        if (v == null || v.isBlank()) return "messages.yml";
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

        // ✅ 1.0.6: async autosave to avoid hitching the main thread
        autosaveTaskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> trySaveNowAsync("autosave"),
                ticks,
                ticks
        ).getTaskId();
    }

    private void trySaveNowAsync(String reason) {
        // Don't overlap saves
        if (!saveInFlight.compareAndSet(false, true)) return;

        try {
            if (storage != null && teams != null) {
                storage.saveAll(teams);
            }
        } catch (Exception e) {
            getLogger().severe("Save failed (" + reason + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            saveInFlight.set(false);
        }
    }

    private void trySaveNowSync(String reason) {
        // If async is saving, skip. On disable we’d rather not deadlock/hang.
        if (!saveInFlight.compareAndSet(false, true)) return;

        try {
            if (storage != null && teams != null) {
                storage.saveAll(teams);
            }
        } catch (Exception e) {
            getLogger().severe("Save failed (" + reason + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            saveInFlight.set(false);
        }
    }

    private void stopTask(int taskId) {
        // treat 0 as a valid id just in case (defensive)
        if (taskId < 0) return;
        try {
            getServer().getScheduler().cancelTask(taskId);
        } catch (Exception ignored) {}
    }

    /**
     * Keeps update checker state consistent with config.
     * We cannot reliably "unregister" listeners mid-runtime, so we:
     * - Register the join listener once
     * - Keep the checker instance available once created
     * - Only perform checks when enabled
     */
    private void syncUpdateCheckerWithConfig() {
        boolean enabled = getConfig().getBoolean("update_checker.enabled", true);

        if (this.updateChecker == null) {
            this.updateChecker = new UpdateChecker(this);
        }

        // Ensure listener exists + registered once
        if (this.updateNotifyListener == null) {
            this.updateNotifyListener = new UpdateNotifyListener(this, updateChecker);
            getServer().getPluginManager().registerEvents(updateNotifyListener, this);
        }

        if (enabled) {
            updateChecker.checkNowAsync();
        }
    }

    // Keep existing API
    public String getMessagesFileName() {
        return getMessagesFileNameSafe();
    }

    public Msg msg() { return msg; }
    public TeamService teams() { return teams; }
    public TeamStorage storage() { return storage; }
    public UpdateChecker updateChecker() { return updateChecker; }

    // 1.0.3
    public TeamInvites invites() { return invites; }
}
