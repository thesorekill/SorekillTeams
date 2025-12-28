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
import net.chumbucket.sorekillteams.listener.SqlBackfillJoinListener;
import net.chumbucket.sorekillteams.listener.TeamChatListener;
import net.chumbucket.sorekillteams.listener.TeamOnlineStatusListener;
import net.chumbucket.sorekillteams.menu.MenuRouter;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamInvites;
import net.chumbucket.sorekillteams.placeholders.PlaceholderBridge;
import net.chumbucket.sorekillteams.service.SimpleTeamHomeService;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamHomeService;
import net.chumbucket.sorekillteams.service.TeamService;
import net.chumbucket.sorekillteams.storage.TeamHomeStorage;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamHomeStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamStorage;
import net.chumbucket.sorekillteams.storage.sql.SqlDatabase;
import net.chumbucket.sorekillteams.storage.sql.SqlDialect;
import net.chumbucket.sorekillteams.storage.sql.SqlTeamHomeStorage;
import net.chumbucket.sorekillteams.storage.sql.SqlTeamStorage;
import net.chumbucket.sorekillteams.storage.sql.YamlToSqlMigrator;
import net.chumbucket.sorekillteams.update.UpdateChecker;
import net.chumbucket.sorekillteams.update.UpdateNotifyListener;
import net.chumbucket.sorekillteams.util.Actionbar;
import net.chumbucket.sorekillteams.util.Debug;
import net.chumbucket.sorekillteams.util.Menus;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.Library;

public final class SorekillTeamsPlugin extends JavaPlugin {

    private Msg msg;
    private Menus menus;
    private Actionbar actionbar;
    private Debug debug;

    private MenuRouter menuRouter;

    private PlaceholderBridge placeholderBridge;

    private TeamStorage storage;
    private TeamService teams;

    private TeamHomeStorage teamHomeStorage;
    private TeamHomeService teamHomes;

    private final TeamInvites invites = new TeamInvites();

    private UpdateChecker updateChecker;
    private UpdateNotifyListener updateNotifyListener;

    private int invitePurgeTaskId = -1;
    private int autosaveTaskId = -1;

    private final AtomicBoolean saveInFlight = new AtomicBoolean(false);

    private boolean placeholdersHooked = false;

    private SqlDatabase sqlDb;
    private SqlDialect sqlDialect;
    private String storageTypeActive = "yaml";

    // cache which driver we already attempted to load this session
    private String loadedJdbcDriverForType = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        saveResourceIfMissing(getMessagesFileNameSafe());
        saveResourceIfMissing(getMenusFileNameSafe());

        this.msg = new Msg(this);
        this.menus = new Menus(this);
        this.actionbar = new Actionbar(this);
        this.debug = new Debug(this);

        this.menuRouter = new MenuRouter(this);

        ensurePlaceholdersHooked();

        try {
            wireStorageFromConfig(true);
        } catch (Exception e) {
            getLogger().severe("Failed to initialize storage backend. Disabling plugin to prevent data loss.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        syncHomesWiringFromConfig(true);

        try {
            storage.loadAll(teams);
        } catch (Exception e) {
            getLogger().severe("Failed to load teams from storage. Disabling plugin to prevent data loss.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadHomesBestEffort("startup");

        registerCommand("sorekillteams", new AdminCommand(this));
        registerCommand("team", new TeamCommand(this));
        registerCommand("tc", new TeamChatCommand(this));

        registerTabCompleter("team", new TeamCommandTabCompleter(this));

        getServer().getPluginManager().registerEvents(new FriendlyFireListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamChatListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamOnlineStatusListener(this), this);
        getServer().getPluginManager().registerEvents(new SqlBackfillJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new MainMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new CreateTeamFlowListener(this), this);

        startInvitePurgeTask();
        startAutosaveTask();

        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams enabled. Storage=" + storageTypeActive);
    }

    @Override
    public void onDisable() {
        stopTask(invitePurgeTaskId);
        invitePurgeTaskId = -1;

        stopTask(autosaveTaskId);
        autosaveTaskId = -1;

        trySaveNowSync("shutdown");

        if (placeholderBridge != null) {
            placeholderBridge.unhookAll();
        }

        stopSql();

        getLogger().info("SorekillTeams disabled.");
    }

    public void reloadEverything() {
        reloadConfig();

        saveResourceIfMissing(getMessagesFileNameSafe());
        saveResourceIfMissing(getMenusFileNameSafe());

        if (msg != null) {
            try { msg.reload(); }
            catch (Exception e) {
                getLogger().warning("Failed to reload messages: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } else msg = new Msg(this);

        if (menus != null) {
            try { menus.reload(); }
            catch (Exception e) {
                getLogger().warning("Failed to reload menus.yml: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } else menus = new Menus(this);

        if (menuRouter == null) menuRouter = new MenuRouter(this);

        ensurePlaceholdersHooked();

        try {
            wireStorageFromConfig(false);
        } catch (Exception e) {
            getLogger().severe("Reload: failed to initialize storage backend. Keeping previous backend.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            TeamService fresh = new SimpleTeamService(this, storage);
            storage.loadAll(fresh);
            this.teams = fresh;

            invites.purgeExpiredAll(System.currentTimeMillis());
        } catch (Exception e) {
            getLogger().severe("Reload failed while loading teams. Keeping previous in-memory teams.");
            getLogger().severe("Reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        syncHomesWiringFromConfig(false);
        loadHomesBestEffort("reload");

        startInvitePurgeTask();
        startAutosaveTask();

        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams reloaded. Storage=" + storageTypeActive);
    }

    private void ensurePlaceholdersHooked() {
        if (placeholderBridge == null) {
            placeholderBridge = new PlaceholderBridge(this);
        }
        if (!placeholdersHooked) {
            placeholderBridge.hookAll();
            placeholdersHooked = true;
        }
    }

    /**
     * Downloads and loads the JDBC driver for the chosen storage type.
     *
     * We keep the plugin jar small by NOT shading any drivers.
     * Instead, we fetch the needed one into ./plugins/SorekillTeams/libraries/ and load it.
     */
    private void loadJdbcDriverIfNeeded(String storageType) {
        if (storageType == null) return;

        String type = storageType.trim().toLowerCase(Locale.ROOT);
        if (type.equals("postgres")) type = "postgresql";
        if (type.equals("pg")) type = "postgresql";

        if (type.equals("yaml")) return;
        if (Objects.equals(loadedJdbcDriverForType, type)) return;

        // Ensure data folder exists (Libby will cache under plugin folder)
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder for library cache: " + getDataFolder().getAbsolutePath());
        }

        final BukkitLibraryManager lm = new BukkitLibraryManager(this);

        // Maven Central
        lm.addRepository("https://repo1.maven.org/maven2/");

        final Library lib;
        switch (type) {
            case "mysql" -> lib = Library.builder()
                    .groupId("com.mysql")
                    .artifactId("mysql-connector-j")
                    .version(getDriverVersionOrThrow("mysql", "mysql.driver.version"))
                    .build();

            case "mariadb" -> lib = Library.builder()
                    .groupId("org.mariadb.jdbc")
                    .artifactId("mariadb-java-client")
                    .version(getDriverVersionOrThrow("mariadb", "mariadb.driver.version"))
                    .build();

            case "postgresql" -> lib = Library.builder()
                    .groupId("org.postgresql")
                    .artifactId("postgresql")
                    .version(getDriverVersionOrThrow("postgresql", "postgres.driver.version"))
                    .build();

            case "sqlite" -> lib = Library.builder()
                    .groupId("org.xerial")
                    .artifactId("sqlite-jdbc")
                    .version(getDriverVersionOrThrow("sqlite", "sqlite.driver.version"))
                    .build();

            case "h2" -> lib = Library.builder()
                    .groupId("com.h2database")
                    .artifactId("h2")
                    .version(getDriverVersionOrThrow("h2", "h2.version"))
                    .build();

            default -> throw new IllegalArgumentException(
                    "Unknown storage.type '" + storageType + "'. Expected: yaml|sqlite|h2|mysql|mariadb|postgresql");
        }

        try {
            getLogger().info("Loading JDBC driver for storage.type=" + type + " (" +
                    lib.getGroupId() + ":" + lib.getArtifactId() + ":" + lib.getVersion() + ")");
            lm.loadLibrary(lib);
            loadedJdbcDriverForType = type;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download/load JDBC driver for storage.type=" + type +
                    ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads a version from system properties first (so you can override),
     * then falls back to hardcoded defaults that match your pom properties.
     *
     * Why: the plugin can't read Maven pom properties at runtime.
     */
    private String getDriverVersionOrThrow(String type, String keyHint) {
        // Allow overriding via JVM: -Dsorekillteams.mysql.driver.version=...
        String sysKey = "sorekillteams." + type + ".driver.version";
        String v = System.getProperty(sysKey);
        if (v != null && !v.isBlank()) return v.trim();

        // Defaults must match the versions in your pom.xml properties
        return switch (type) {
            case "mysql" -> "9.1.0";
            case "mariadb" -> "3.5.0";
            case "postgresql" -> "42.7.4";
            case "sqlite" -> "3.46.1.0";
            case "h2" -> "2.3.232";
            default -> throw new IllegalStateException("Missing driver version mapping for type=" + type + " (" + keyHint + ")");
        };
    }

    private void wireStorageFromConfig(boolean isStartup) {
        String type = getConfig().getString("storage.type", "yaml");
        type = (type == null ? "yaml" : type.trim().toLowerCase(Locale.ROOT));
        if (type.equals("postgres")) type = "postgresql";
        if (type.equals("pg")) type = "postgresql";

        if (!isStartup && type.equalsIgnoreCase(storageTypeActive)) {
            if (teams == null && storage != null) {
                teams = new SimpleTeamService(this, storage);
            }
            return;
        }

        if (type.equals("yaml")) {
            stopSql();

            this.storage = (this.storage instanceof YamlTeamStorage && !isStartup)
                    ? this.storage
                    : new YamlTeamStorage(this);

            this.teams = new SimpleTeamService(this, storage);
            this.storageTypeActive = "yaml";
            this.sqlDialect = null;
            return;
        }

        // ✅ Ensure the JDBC driver exists before starting SQL
        loadJdbcDriverIfNeeded(type);

        SqlDialect dialect = SqlDialect.fromStorageType(type);

        stopSql();
        this.sqlDialect = dialect;

        ConfigurationSection sql = getConfig().getConfigurationSection("storage.sql");
        if (sql == null) throw new IllegalStateException("Missing config section: storage.sql");

        String prefix = sql.getString("table_prefix", "st_");
        SqlDatabase db = new SqlDatabase(this, dialect, prefix);

        db.start(sql);

        try {
            new YamlToSqlMigrator(this, db).migrateIfNeededOnStartup();
        } catch (Exception e) {
            stopSql();
            throw new IllegalStateException("YAML -> SQL migration failed: " +
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        this.sqlDb = db;

        this.storage = new SqlTeamStorage(db);
        this.teams = new SimpleTeamService(this, storage);
        this.storageTypeActive = type;
    }

    private void stopSql() {
        if (sqlDb != null) {
            try { sqlDb.stop(); } catch (Exception ignored) {}
            sqlDb = null;
        }
        sqlDialect = null;
        // keep loadedJdbcDriverForType as-is; no need to unload jars
    }

    private void syncHomesWiringFromConfig(boolean isStartup) {
        boolean homesEnabled = getConfig().getBoolean("homes.enabled", false);

        if (!homesEnabled) {
            teamHomeStorage = null;
            teamHomes = null;
            return;
        }

        if (teamHomes == null || !isStartup) {
            teamHomes = buildTeamHomeService();
        }

        if (storageTypeActive.equalsIgnoreCase("yaml")) {
            if (teamHomeStorage == null || !(teamHomeStorage instanceof YamlTeamHomeStorage)) {
                teamHomeStorage = new YamlTeamHomeStorage(this);
            }
            return;
        }

        if (sqlDb == null) {
            getLogger().warning("Homes enabled but SQL DB is not initialized. Falling back to YAML homes.");
            teamHomeStorage = new YamlTeamHomeStorage(this);
            return;
        }

        if (teamHomeStorage == null || !(teamHomeStorage instanceof SqlTeamHomeStorage)) {
            teamHomeStorage = new SqlTeamHomeStorage(sqlDb);
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
            getLogger().warning("Failed to load team homes (" + phase + "): " +
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =========================================================
    // OPTION 3: Snapshot + membership refresh
    // =========================================================

    private final ConcurrentHashMap<UUID, Long> lastSqlMembershipCheckMs = new ConcurrentHashMap<>();
    private final AtomicBoolean snapshotRefreshInFlight = new AtomicBoolean(false);
    private volatile long lastSnapshotRefreshMs = 0L;

    public void ensureTeamsSnapshotFreshFromSql() {
        if ("yaml".equalsIgnoreCase(storageTypeActive)) return;

        if (!(teams instanceof SimpleTeamService simple)) return;
        if (!(storage instanceof SqlTeamStorage sqlStorage)) return;

        long now = System.currentTimeMillis();
        long ttlMs = Math.max(250L, getConfig().getLong("storage.sql_snapshot_refresh_ttl_ms", 1500L));
        if (now - lastSnapshotRefreshMs < ttlMs) return;

        if (!snapshotRefreshInFlight.compareAndSet(false, true)) return;

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Collection<Team> loaded;

            try {
                loaded = sqlStorage.loadAllTeamsSnapshot();
            } catch (Exception e) {
                getLogger().warning("SQL snapshot refresh failed: " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                snapshotRefreshInFlight.set(false);
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                try {
                    simple.replaceTeamsSnapshot(loaded);
                    lastSnapshotRefreshMs = System.currentTimeMillis(); // ✅ only after success
                } finally {
                    snapshotRefreshInFlight.set(false);
                }
            });
        });
    }

    public void ensureTeamFreshFromSql(UUID playerUuid) {
        if (playerUuid == null) return;

        if ("yaml".equalsIgnoreCase(storageTypeActive)) return;
        if (!(teams instanceof SimpleTeamService simple)) return;
        if (!(storage instanceof SqlTeamStorage sqlStorage)) return;

        long now = System.currentTimeMillis();
        long ttlMs = Math.max(250L, getConfig().getLong("storage.sql_membership_refresh_ttl_ms", 1500L));

        long last = lastSqlMembershipCheckMs.getOrDefault(playerUuid, 0L);
        if (now - last < ttlMs) return;
        lastSqlMembershipCheckMs.put(playerUuid, now);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            UUID sqlTeamId = null;
            Team loadedTeam = null;

            try {
                sqlTeamId = sqlStorage.findTeamIdForMember(playerUuid);

                if (sqlTeamId != null) {
                    loadedTeam = sqlStorage.loadTeamById(sqlTeamId);
                    if (loadedTeam == null) sqlTeamId = null;
                }
            } catch (Exception e) {
                getLogger().warning("SQL membership refresh failed for " + playerUuid + ": " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                return;
            }

            UUID finalSqlTeamId = sqlTeamId;
            Team finalLoadedTeam = loadedTeam;

            getServer().getScheduler().runTask(this, () -> {
                UUID currentCached = simple.getTeamByPlayer(playerUuid).map(Team::getId).orElse(null);
                if (Objects.equals(currentCached, finalSqlTeamId)) return;

                if (finalSqlTeamId == null) {
                    simple.clearCachedMembership(playerUuid);
                    return;
                }

                if (finalLoadedTeam != null) {
                    simple.putLoadedTeam(finalLoadedTeam);
                } else {
                    simple.clearCachedMembership(playerUuid);
                }
            });
        });
    }

    public void ensureTeamFreshFromSql(Player player) {
        if (player == null) return;
        ensureTeamFreshFromSql(player.getUniqueId());
    }

    // =========================================================

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
        try { v = getConfig().getString("files.messages", "messages.yml"); }
        catch (Exception ignored) {}
        return (v == null || v.isBlank()) ? "messages.yml" : v.trim();
    }

    private String getMenusFileNameSafe() {
        String v = null;
        try { v = getConfig().getString("files.menus", "menus.yml"); }
       catch (Exception ignored) {}
        return (v == null || v.isBlank()) ? "menus.yml" : v.trim();
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

    // ✅ autosave must NOT rewrite SQL from stale caches.
    private void trySaveNowAsync(String reason) {
        if (!saveInFlight.compareAndSet(false, true)) return;

        try {
            TeamStorage s = this.storage;
            TeamService t = this.teams;

            if (s != null && t != null) {
                if (t instanceof SimpleTeamService simple) {
                    if (simple.isDirty()) {
                        s.saveAll(t);
                    }
                } else {
                    s.saveAll(t);
                }
            }

            TeamHomeStorage hs = this.teamHomeStorage;
            TeamHomeService hv = this.teamHomes;
            if (hs != null && hv != null) hs.saveAll(hv);

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
                if (t instanceof SimpleTeamService simple) {
                    if (simple.isDirty()) {
                        s.saveAll(t);
                    }
                } else {
                    s.saveAll(t);
                }
            }

            TeamHomeStorage hs = this.teamHomeStorage;
            TeamHomeService hv = this.teamHomes;
            if (hs != null && hv != null) hs.saveAll(hv);

        } catch (Exception e) {
            getLogger().severe("Save failed (" + reason + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            saveInFlight.set(false);
        }
    }

    private void stopTask(int taskId) {
        if (taskId < 0) return;
        try { getServer().getScheduler().cancelTask(taskId); }
        catch (Exception ignored) {}
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

        if (enabled) updateChecker.checkNowAsync();
        else getLogger().info("UpdateChecker disabled in config.");
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

    public MenuRouter menuRouter() { return menuRouter; }

    public PlaceholderBridge placeholders() { return placeholderBridge; }

    public TeamService teams() { return teams; }
    public TeamStorage storage() { return storage; }

    public TeamInvites invites() { return invites; }

    public UpdateChecker updateChecker() { return updateChecker; }

    public TeamHomeService teamHomes() { return teamHomes; }
    public TeamHomeStorage teamHomeStorage() { return teamHomeStorage; }

    public boolean isFriendlyFireToggleEnabled() {
        return getConfig().getBoolean("friendly_fire.toggle_enabled", true);
    }

    public String storageTypeActive() {
        return storageTypeActive;
    }

    public SqlDialect sqlDialect() {
        return sqlDialect;
    }
}
