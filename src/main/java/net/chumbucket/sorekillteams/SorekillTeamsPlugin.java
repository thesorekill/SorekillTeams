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

import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.Library;
import net.chumbucket.sorekillteams.command.AdminCommand;
import net.chumbucket.sorekillteams.command.AdminCommandTabCompleter;
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
import net.chumbucket.sorekillteams.network.InvitePacket;
import net.chumbucket.sorekillteams.network.PresencePacket;
import net.chumbucket.sorekillteams.network.RedisInviteBus;
import net.chumbucket.sorekillteams.network.RedisPresenceBus;
import net.chumbucket.sorekillteams.network.RedisTeamChatBus;
import net.chumbucket.sorekillteams.network.RedisTeamEventBus;
import net.chumbucket.sorekillteams.network.TeamChatBus;
import net.chumbucket.sorekillteams.network.TeamChatPacket;
import net.chumbucket.sorekillteams.network.TeamEventPacket;
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
import net.chumbucket.sorekillteams.storage.sql.SqlTeamInviteStorage;
import net.chumbucket.sorekillteams.storage.sql.SqlTeamStorage;
import net.chumbucket.sorekillteams.storage.sql.YamlToSqlMigrator;
import net.chumbucket.sorekillteams.update.UpdateChecker;
import net.chumbucket.sorekillteams.update.UpdateNotifyListener;
import net.chumbucket.sorekillteams.util.Actionbar;
import net.chumbucket.sorekillteams.util.Debug;
import net.chumbucket.sorekillteams.util.Menus;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
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

    // YAML-mode invites cache (existing behavior)
    private final TeamInvites invites = new TeamInvites();

    // ✅ SQL-mode invites storage
    private SqlTeamInviteStorage sqlInvites;

    private UpdateChecker updateChecker;
    private UpdateNotifyListener updateNotifyListener;

    private int invitePurgeTaskId = -1;
    private int autosaveTaskId = -1;

    private int sqlRefreshTaskId = -1;

    private final AtomicBoolean saveInFlight = new AtomicBoolean(false);

    private boolean placeholdersHooked = false;

    private SqlDatabase sqlDb;
    private SqlDialect sqlDialect;
    private String storageTypeActive = "yaml";

    // =========================================================
    // Cross-server buses (Redis Pub/Sub)
    // =========================================================
    private TeamChatBus teamChatBus;
    private RedisInviteBus inviteBus;

    // ✅ Team event bus (leave/kick/disband/rename/transfer/join)
    private RedisTeamEventBus teamEventBus;

    // ✅ Presence bus (network-wide online/offline)
    private RedisPresenceBus presenceBus;

    private String networkServerName = "default";

    private String loadedJdbcDriverForType = null;

    // =========================================================
    // ✅ Redis runtime libs (keep jar small)
    // =========================================================
    private volatile boolean redisLibsLoaded = false;

    // =========================================================
    // ✅ Presence de-dupe (stop spam / only proxy join+leave should message)
    // =========================================================
    private final ConcurrentHashMap<UUID, PresenceState> presenceState = new ConcurrentHashMap<>();

    private static final class PresenceState {
        volatile boolean online;
        volatile long lastOnlineMs;
        volatile long lastOfflineMs;
    }

    private long presenceSwapWindowMs() {
        // Backend swap bursts usually < 2s. Keep a little padding.
        return Math.max(1000L, getConfig().getLong("redis.presence.swap_window_ms", 2500L));
    }

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

        // -------------------------
        // Storage boot
        // -------------------------
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

        // -------------------------
        // ✅ Network wiring (Redis buses)
        // -------------------------
        try {
            setupRedisNetwork();
        } catch (Throwable t) {
            // Don’t hard-disable the plugin if redis fails; just log and run local-only.
            getLogger().warning("Redis network failed to start. Running in local-only mode.");
            getLogger().warning("Reason: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // -------------------------
        // ✅ Commands + tab completion
        // -------------------------
        // IMPORTANT:
        // - The alias "st" inherits tab completion from "sorekillteams" automatically.
        // - Do NOT try getCommand("st") (Spigot returns the main command, not the alias).
        registerCommand("team", new TeamCommand(this));
        registerCommand("tc", new TeamChatCommand(this));
        registerCommand("sorekillteams", new AdminCommand(this));

        registerTabCompleter("team", new TeamCommandTabCompleter(this));
        registerTabCompleter("sorekillteams", new AdminCommandTabCompleter(this));

        // Extra safety + useful logs
        PluginCommand adminCmd = getCommand("sorekillteams");
        if (adminCmd == null) {
            getLogger().warning("Command 'sorekillteams' not found. Check plugin.yml: commands: sorekillteams");
        } else {
            getLogger().info("Registered /sorekillteams executor + tab completer (aliases inherit).");
        }

        // -------------------------
        // Listeners
        // -------------------------
        getServer().getPluginManager().registerEvents(new FriendlyFireListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamChatListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamOnlineStatusListener(this), this);
        getServer().getPluginManager().registerEvents(new SqlBackfillJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new MainMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new CreateTeamFlowListener(this), this);

        // -------------------------
        // Tasks
        // -------------------------
        startInvitePurgeTask();
        startAutosaveTask();
        startSqlAutoRefreshTask();

        // -------------------------
        // Misc
        // -------------------------
        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams enabled. Storage=" + storageTypeActive +
                (isRedisNetworkEnabled()
                        ? " RedisNetwork=ON(" + networkServerName + ")"
                        : " RedisNetwork=OFF"));
    }

    @Override
    public void onDisable() {
        stopTask(invitePurgeTaskId);
        invitePurgeTaskId = -1;

        stopTask(autosaveTaskId);
        autosaveTaskId = -1;

        stopSqlAutoRefreshTask();

        trySaveNowSync("shutdown");

        if (placeholderBridge != null) {
            placeholderBridge.unhookAll();
        }

        stopRedisNetwork();
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

        // reset dedupe state on reload to avoid “stuck” suppressions
        presenceState.clear();

        syncHomesWiringFromConfig(false);
        loadHomesBestEffort("reload");

        startInvitePurgeTask();
        startAutosaveTask();
        startSqlAutoRefreshTask();

        // ✅ restart/refresh network wiring too (safely)
        try {
            setupRedisNetwork();
        } catch (Throwable t) {
            getLogger().warning("Reload: Redis network failed to start. Running in local-only mode.");
            getLogger().warning("Reason: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        syncUpdateCheckerWithConfig();

        getLogger().info("SorekillTeams reloaded. Storage=" + storageTypeActive +
                (isRedisNetworkEnabled()
                        ? " RedisNetwork=ON(" + networkServerName + ")"
                        : " RedisNetwork=OFF"));
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

    // =========================================================
    // ✅ Redis network wiring (chat + invites + team events + presence)
    // =========================================================

    private void setupRedisNetwork() {
        stopRedisNetwork();

        boolean enabled = getConfig().getBoolean("redis.enabled", false);
        if (!enabled) return;

        ConfigurationSection sec = getConfig().getConfigurationSection("redis");
        if (sec == null) {
            getLogger().warning("redis.enabled=true but missing 'redis:' section.");
            return;
        }

        String serverId = sec.getString("server_id", "default");
        this.networkServerName = (serverId == null || serverId.isBlank()) ? "default" : serverId.trim();

        // ✅ MUST load jedis runtime libs BEFORE any Redis classes run
        loadRedisLibsIfNeeded(sec);

        try {
            this.teamChatBus = new RedisTeamChatBus(this, sec, networkServerName);
            this.teamChatBus.start();
        } catch (Exception e) {
            this.teamChatBus = null;
            getLogger().warning("Failed to start Redis team chat bus: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            this.inviteBus = new RedisInviteBus(this, sec, networkServerName);
            this.inviteBus.start();
        } catch (Exception e) {
            this.inviteBus = null;
            getLogger().warning("Failed to start Redis invite bus: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            this.teamEventBus = new RedisTeamEventBus(this, sec, networkServerName);
            this.teamEventBus.start();
        } catch (Exception e) {
            this.teamEventBus = null;
            getLogger().warning("Failed to start Redis team event bus: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            this.presenceBus = new RedisPresenceBus(this, sec, networkServerName);
            this.presenceBus.start();
        } catch (Exception e) {
            this.presenceBus = null;
            getLogger().warning("Failed to start Redis presence bus: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void stopRedisNetwork() {
        TeamChatBus bus = this.teamChatBus;
        this.teamChatBus = null;
        if (bus != null) {
            try { bus.stop(); } catch (Exception ignored) {}
        }

        RedisInviteBus ib = this.inviteBus;
        this.inviteBus = null;
        if (ib != null) {
            try { ib.stop(); } catch (Exception ignored) {}
        }

        RedisTeamEventBus teb = this.teamEventBus;
        this.teamEventBus = null;
        if (teb != null) {
            try { teb.stop(); } catch (Exception ignored) {}
        }

        RedisPresenceBus pb = this.presenceBus;
        this.presenceBus = null;
        if (pb != null) {
            try { pb.stop(); } catch (Exception ignored) {}
        }
    }

    /**
     * Backwards-compatible behavior:
     * Redis enabled AND at least one bus is alive.
     */
    public boolean isRedisNetworkEnabled() {
        boolean enabled = getConfig().getBoolean("redis.enabled", false);
        if (!enabled) return false;

        boolean chatOk = teamChatBus != null && teamChatBus.isRunning();
        boolean invitesOk = inviteBus != null && inviteBus.isRunning();
        boolean eventsOk = teamEventBus != null && teamEventBus.isRunning();
        boolean presenceOk = presenceBus != null && presenceBus.isRunning();

        return chatOk || invitesOk || eventsOk || presenceOk;
    }

    public boolean isPresenceNetworkEnabled() {
        return presenceBus != null && presenceBus.isRunning();
    }

    public String networkServerName() {
        return networkServerName;
    }

    public void publishTeamChat(TeamChatPacket packet) {
        if (packet == null) return;
        TeamChatBus bus = this.teamChatBus;
        if (bus == null || !bus.isRunning()) return;
        bus.publish(packet);
    }

    public void publishInvite(InvitePacket packet) {
        if (packet == null) return;
        RedisInviteBus bus = this.inviteBus;
        if (bus == null || !bus.isRunning()) return;
        bus.publish(packet);
    }

    public void publishTeamEvent(TeamEventPacket packet) {
        if (packet == null) return;
        RedisTeamEventBus bus = this.teamEventBus;
        if (bus == null || !bus.isRunning()) return;
        bus.publish(packet);
    }

    public void markPresenceOnline(Player p) {
        RedisPresenceBus pb = this.presenceBus;
        if (pb == null || !pb.isRunning() || p == null) return;
        pb.markOnline(p);
    }

    public void markPresenceOffline(UUID uuid, String nameHint) {
        RedisPresenceBus pb = this.presenceBus;
        if (pb == null || !pb.isRunning() || uuid == null) return;
        pb.markOffline(uuid, nameHint);
    }

    public boolean isOnlineNetwork(UUID uuid) {
        RedisPresenceBus pb = this.presenceBus;
        if (pb == null || !pb.isRunning()) return false;
        return pb.isOnlineNetwork(uuid);
    }

    /**
     * Called by RedisInviteBus on the main thread after it receives a packet
     * (and after it ignores self-origin).
     */
    public void onRemoteInviteEvent(InvitePacket pkt) {
        if (pkt == null) return;

        try {
            ensureTeamsSnapshotFreshFromSql();
            ensureTeamFreshFromSql(pkt.inviteeUuid());
            ensureTeamFreshFromSql(pkt.inviterUuid());
        } catch (Throwable ignored) {}

        Player invitee = Bukkit.getPlayer(pkt.inviteeUuid());
        Player inviter = Bukkit.getPlayer(pkt.inviterUuid());

        switch (pkt.type()) {
            case SENT -> {
                if (invitee != null && invitee.isOnline()) {
                    msg().send(invitee, "team_invite_received",
                            "{inviter}", safe(pkt.inviterName(), "unknown"),
                            "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                    );
                    if (actionbar != null) {
                        actionbar.send(invitee, "actionbar.team_invite_received",
                                "{inviter}", safe(pkt.inviterName(), "unknown"),
                                "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                        );
                    }
                }
            }
            case ACCEPTED -> {
                if (inviter != null && inviter.isOnline()) {
                    String who = (invitee != null && invitee.getName() != null)
                            ? invitee.getName()
                            : safe(pkt.inviteeNameFallback(), "unknown");

                    msg().send(inviter, "team_invite_accepted_inviter",
                            "{player}", who,
                            "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                    );
                }

                UUID tid = pkt.teamId();
                if (tid != null && menuRouter != null) {
                    Bukkit.getScheduler().runTask(this, () -> menuRouter.refreshTeamMenusForLocalViewers(tid));
                }
            }
            case DENIED -> { }
            case EXPIRED -> { }
            case CANCELLED -> { }
        }
    }

    // =========================================================
    // ✅ Remote TeamEvent de-dupe (prevents double-processing)
    // =========================================================
    private final ConcurrentHashMap<String, Long> recentTeamEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> recentKickNotifies = new ConcurrentHashMap<>();

    private long teamEventDedupeWindowMs() {
        return Math.max(250L, getConfig().getLong("redis.team_events.dedupe_window_ms", 4000L));
    }

    private boolean shouldProcessTeamEventOnce(TeamEventPacket pkt) {
        if (pkt == null || pkt.type() == null || pkt.teamId() == null) return false;

        long now = System.currentTimeMillis();
        long window = teamEventDedupeWindowMs();

        final String key =
                pkt.type().name() + "|" +
                        pkt.teamId() + "|" +
                        String.valueOf(pkt.actorUuid()) + "|" +
                        String.valueOf(pkt.targetUuid());

        Long prev = recentTeamEvents.get(key);
        if (prev != null) {
            long dt = now - prev;
            if (dt >= 0 && dt <= window) {
                return false;
            }
        }

        recentTeamEvents.put(key, now);

        if ((now & 63) == 0) {
            long killBefore = now - (window * 4L);
            for (var it = recentTeamEvents.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() < killBefore) it.remove();
            }
        }

        return true;
    }

    private boolean shouldNotifyKickedOnce(UUID targetUuid) {
        if (targetUuid == null) return false;

        long now = System.currentTimeMillis();
        long window = Math.max(250L, getConfig().getLong("redis.team_events.kick_notify_dedupe_ms", 4000L));

        Long prev = recentKickNotifies.get(targetUuid);
        if (prev != null) {
            long dt = now - prev;
            if (dt >= 0 && dt <= window) return false;
        }

        recentKickNotifies.put(targetUuid, now);

        if ((now & 63) == 0) {
            long killBefore = now - (window * 4L);
            for (var it = recentKickNotifies.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() < killBefore) it.remove();
            }
        }

        return true;
    }

    /**
     * Called by RedisTeamEventBus on main thread.
     * Messaging is event-driven (Redis). SQL refresh is state-only.
     */
    public void onRemoteTeamEvent(TeamEventPacket pkt) {
        if (pkt == null) return;

        // Dedup protection
        if (!shouldProcessTeamEventOnce(pkt)) {
            return;
        }

        // Refresh team snapshot / membership caches (best-effort)
        try {
            ensureTeamsSnapshotFreshFromSql();
            if (pkt.actorUuid() != null) ensureTeamFreshFromSql(pkt.actorUuid());
            if (pkt.targetUuid() != null) ensureTeamFreshFromSql(pkt.targetUuid());
        } catch (Throwable ignored) {}

        // ✅ NEW: homes events require homes snapshot refresh so menus render correct state
        boolean isHomeEvent =
                pkt.type() == TeamEventPacket.Type.HOME_SET
                || pkt.type() == TeamEventPacket.Type.HOME_DELETED
                || pkt.type() == TeamEventPacket.Type.HOME_CLEARED;

        if (isHomeEvent) {
            try {
                // Your method already exists and is safe (you call it from menu_open)
                loadHomesBestEffort("remote_team_event:" + pkt.type().name());
            } catch (Throwable ignored) {}
        }

        final String key;
        final String[] pairs;

        switch (pkt.type()) {
            case MEMBER_JOINED -> {
                key = "team_member_joined";
                pairs = new String[]{
                        "{player}", safe(pkt.targetName(), safe(pkt.actorName(), "unknown")),
                        "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                };
            }
            case MEMBER_LEFT -> {
                key = "team_member_left";
                pairs = new String[]{
                        "{player}", safe(pkt.targetName(), safe(pkt.actorName(), "unknown")),
                        "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                };
            }
            case MEMBER_KICKED -> {
                key = "team_member_kicked_broadcast";
                pairs = new String[]{
                        "{player}", safe(pkt.targetName(), "unknown"),
                        "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                };

                // Notify kicked target (only once, only if on this backend)
                if (pkt.targetUuid() != null && shouldNotifyKickedOnce(pkt.targetUuid())) {
                    Player kicked = Bukkit.getPlayer(pkt.targetUuid());
                    if (kicked != null && kicked.isOnline()) {
                        msg().send(kicked, "team_kick_target",
                                "{team}", Msg.color(safe(pkt.teamName(), "Team")),
                                "{by}", safe(pkt.actorName(), "unknown")
                        );
                    }
                }
            }
            case TEAM_DISBANDED -> {
                key = "team_team_disbanded";
                pairs = new String[]{
                        "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                };
            }
            case TEAM_RENAMED -> {
                key = "team_renamed_broadcast";
                pairs = new String[]{
                        "{team}", Msg.color(safe(pkt.teamName(), "Team")),
                        "{old}", Msg.color(safe(pkt.targetName(), "Team")),
                        "{by}", safe(pkt.actorName(), "unknown")
                };
            }
            case OWNER_TRANSFERRED -> {
                key = "team_owner_transferred_broadcast";
                pairs = new String[]{
                        "{owner}", safe(pkt.targetName(), "unknown"),
                        "{team}", Msg.color(safe(pkt.teamName(), "Team"))
                };

                if (pkt.targetUuid() != null) {
                    Player newOwner = Bukkit.getPlayer(pkt.targetUuid());
                    if (newOwner != null && newOwner.isOnline()) {
                        msg().send(newOwner, "team_transfer_received",
                                "{team}", Msg.color(safe(pkt.teamName(), "Team")),
                                "{by}", safe(pkt.actorName(), "unknown")
                        );
                    }
                }
            }

            // ✅ NEW: home events (no team-wide broadcast message by default)
            // If you later add message keys, you can broadcast them here.
            case HOME_SET, HOME_DELETED, HOME_CLEARED -> {
                key = null;
                pairs = null;
            }

            default -> { return; }
        }

        // Only broadcast chat messages for non-home events
        if (key != null) {
            broadcastToLocalOnlineMembersOfTeam(pkt.teamId(), key, pairs, pkt.actorUuid(), pkt.targetUuid());
        }

        // ✅ Menu handling (team menus + homes)
        if (menuRouter != null && pkt.teamId() != null) {
            final UUID teamId = pkt.teamId();

            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    switch (pkt.type()) {
                        case MEMBER_LEFT, MEMBER_JOINED, OWNER_TRANSFERRED, TEAM_RENAMED -> {
                            menuRouter.refreshTeamMenusForLocalViewers(teamId);
                        }
                        case MEMBER_KICKED -> {
                            if (pkt.targetUuid() != null) {
                                Player kicked = Bukkit.getPlayer(pkt.targetUuid());
                                if (kicked != null && kicked.isOnline()) {
                                    menuRouter.closeIfViewingTeam(kicked, teamId, "team_info", "team_members");
                                }
                            }
                            menuRouter.refreshTeamMenusForLocalViewers(teamId);
                        }
                        case TEAM_DISBANDED -> {
                            menuRouter.closeTeamMenusForLocalViewers(teamId);
                        }

                        // ✅ NEW: home events -> refresh menus so bed + homes list update immediately
                        case HOME_SET, HOME_DELETED, HOME_CLEARED -> {
                            menuRouter.refreshTeamMenusForLocalViewers(teamId);
                        }

                        default -> { /* no-op */ }
                    }
                } catch (Throwable ignored) {}
            });
        }

        // Cache hygiene on disband (prevents phantom teams in browse)
        if (pkt.type() == TeamEventPacket.Type.TEAM_DISBANDED && teams instanceof SimpleTeamService simple) {
            try { simple.evictCachedTeam(pkt.teamId()); } catch (Throwable ignored) {}
        }
    }

    private void broadcastToLocalOnlineMembersOfTeam(UUID teamId,
                                                     String messageKey,
                                                     String[] pairs,
                                                     UUID... exclude) {
        if (teamId == null || messageKey == null || messageKey.isBlank()) return;
        if (teams == null) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null) continue;

            UUID u = p.getUniqueId();
            if (u == null) continue;

            if (exclude != null) {
                boolean skip = false;
                for (UUID ex : exclude) {
                    if (ex != null && ex.equals(u)) { skip = true; break; }
                }
                if (skip) continue;
            }

            Team t = teams.getTeamByPlayer(u).orElse(null);
            if (t == null) continue;
            if (!teamId.equals(t.getId())) continue;

            msg().send(p, messageKey, pairs);
        }
    }

    // =========================================================
    // ✅ Presence: broadcast to teammates on THIS backend
    // =========================================================

    public void onRemotePresence(PresencePacket pkt) {
        if (pkt == null || pkt.playerUuid() == null) return;

        try { ensureTeamFreshFromSql(pkt.playerUuid()); } catch (Throwable ignored) {}

        if (!shouldBroadcastPresence(pkt)) {
            if (debug != null && debug.enabled()) {
                getLogger().info("[PRESENCE-DBG] suppressed " + pkt.type()
                        + " uuid=" + pkt.playerUuid()
                        + " name=" + safe(pkt.playerName(), "?")
                        + " origin=" + safe(pkt.originServer(), "?"));
            }
            return;
        }

        Team team = (teams == null) ? null : teams.getTeamByPlayer(pkt.playerUuid()).orElse(null);
        if (team == null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Team t2 = (teams == null) ? null : teams.getTeamByPlayer(pkt.playerUuid()).orElse(null);
                if (t2 == null) return;
                if (!shouldBroadcastPresence(pkt)) return;
                broadcastPresenceToLocalTeammates(t2.getId(), pkt);
            }, 10L);
            return;
        }

        broadcastPresenceToLocalTeammates(team.getId(), pkt);
    }

    private boolean shouldBroadcastPresence(PresencePacket pkt) {
        UUID uuid = pkt.playerUuid();
        long now = System.currentTimeMillis();

        PresenceState st = presenceState.computeIfAbsent(uuid, k -> new PresenceState());
        long window = presenceSwapWindowMs();

        if (pkt.type() == PresencePacket.Type.ONLINE) {
            if (st.online) return false;

            long dt = now - st.lastOfflineMs;
            if (st.lastOfflineMs > 0 && dt >= 0 && dt <= window) {
                st.online = true;
                st.lastOnlineMs = now;
                return false;
            }

            st.online = true;
            st.lastOnlineMs = now;
            return true;
        }

        if (!st.online) return false;

        long dt = now - st.lastOnlineMs;
        if (st.lastOnlineMs > 0 && dt >= 0 && dt <= window) {
            st.online = false;
            st.lastOfflineMs = now;
            return false;
        }

        st.online = false;
        st.lastOfflineMs = now;
        return true;
    }

    private void broadcastPresenceToLocalTeammates(UUID teamId, PresencePacket pkt) {
        if (teamId == null || pkt == null) return;

        String key = (pkt.type() == PresencePacket.Type.ONLINE) ? "team_member_online" : "team_member_offline";

        broadcastToLocalOnlineMembersOfTeam(teamId, pkt.playerUuid(), key,
                "{player}", safe(pkt.playerName(), pkt.playerUuid().toString().substring(0, 8)),
                "{team}", Msg.color(resolveTeamName(teamId))
        );
    }

    private String resolveTeamName(UUID teamId) {
        if (teamId == null || teams == null) return "Team";
        return teams.getTeamById(teamId).map(Team::getName).orElse("Team");
    }

    private void broadcastToLocalOnlineMembersOfTeam(UUID teamId, UUID exclude, String key, String... pairs) {
        if (teamId == null || key == null || key.isBlank()) return;
        if (teams == null) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null) continue;
            UUID u = p.getUniqueId();
            if (u == null) continue;
            if (exclude != null && exclude.equals(u)) continue;

            Team t = teams.getTeamByPlayer(u).orElse(null);
            if (t == null || t.getId() == null) continue;
            if (!teamId.equals(t.getId())) continue;

            msg().send(p, key, pairs);
        }
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // =========================================================
    // Team chat delivery from Redis
    // =========================================================

    public void broadcastRemoteTeamChat(UUID teamId, UUID senderUuid, String senderName, String formattedMessage) {
        if (teamId == null || senderUuid == null) return;
        if (formattedMessage == null || formattedMessage.isBlank()) return;
        if (teams == null) return;

        Team t = teams.getTeamById(teamId).orElse(null);
        if (t == null) return;

        boolean debugChat = getConfig().getBoolean("chat.debug", false);

        String msgOut = formattedMessage;
        if (debugChat) msgOut = msgOut + Msg.color(" &8[&bREMOTE&8]");

        java.util.HashSet<UUID> sent = new java.util.HashSet<>();

        java.util.LinkedHashSet<UUID> targets = new java.util.LinkedHashSet<>();
        if (t.getOwner() != null) targets.add(t.getOwner());
        if (t.getMembers() != null) targets.addAll(t.getMembers());

        for (UUID u : targets) {
            if (u == null) continue;
            if (u.equals(senderUuid)) continue;

            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                p.sendMessage(msgOut);
                sent.add(u);
            }
        }

        final boolean spyEnabled = getConfig().getBoolean("chat.spy.enabled", true);
        if (!spyEnabled) return;

        final String spyPerm = "sorekillteams.spy";

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == null || !viewer.isOnline()) continue;

            UUID vu = viewer.getUniqueId();
            if (vu == null) continue;
            if (vu.equals(senderUuid)) continue;
            if (sent.contains(vu)) continue;

            if (!viewer.hasPermission(spyPerm)) continue;

            try {
                boolean spyingThisTeam = false;
                Collection<Team> spied = teams.getSpiedTeams(vu);

                if (spied != null) {
                    for (Team st : spied) {
                        if (st != null && teamId.equals(st.getId())) {
                            spyingThisTeam = true;
                            break;
                        }
                    }
                }

                if (!spyingThisTeam) continue;

            } catch (Throwable ignored) {
                continue;
            }

            viewer.sendMessage(Msg.color("&8[&cSPY&8] ") + msgOut);
            sent.add(vu);
        }
    }

    // =========================================================
    // ✅ Redis: runtime load (keeps plugin jar small)
    // =========================================================

    private void loadRedisLibsIfNeeded(ConfigurationSection sec) {
        if (redisLibsLoaded) return;
        if (sec == null) return;

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder for library cache: " + getDataFolder().getAbsolutePath());
        }

        final BukkitLibraryManager lm = new BukkitLibraryManager(this);
        lm.addRepository("https://repo1.maven.org/maven2/");

        String jedisV = sec.getString("jedis_version", "5.2.0");
        String poolV = sec.getString("commons_pool2_version", "2.12.0");
        String slf4jV = sec.getString("slf4j_api_version", "2.0.13");

        final Library slf4jApi = Library.builder()
                .groupId("org.slf4j")
                .artifactId("slf4j-api")
                .version(slf4jV)
                .build();

        final Library pool2 = Library.builder()
                .groupId("org.apache.commons")
                .artifactId("commons-pool2")
                .version(poolV)
                .build();

        final Library jedis = Library.builder()
                .groupId("redis.clients")
                .artifactId("jedis")
                .version(jedisV)
                .build();

        try {
            getLogger().info("Loading Redis libraries via Libby (jedis=" + jedisV + ", pool2=" + poolV + ", slf4j-api=" + slf4jV + ")");
            lm.loadLibrary(slf4jApi);
            lm.loadLibrary(pool2);
            lm.loadLibrary(jedis);
            redisLibsLoaded = true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download/load Redis libraries: " +
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    // =========================================================
    // JDBC runtime loading
    // =========================================================

    private void loadJdbcDriverIfNeeded(String storageType) {
        if (storageType == null) return;

        String type = storageType.trim().toLowerCase(Locale.ROOT);
        if (type.equals("postgres")) type = "postgresql";
        if (type.equals("pg")) type = "postgresql";

        if (type.equals("yaml")) return;
        if (Objects.equals(loadedJdbcDriverForType, type)) return;

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder for library cache: " + getDataFolder().getAbsolutePath());
        }

        final BukkitLibraryManager lm = new BukkitLibraryManager(this);
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
                    .version(getDriverVersionOrThrow("sqlite", "sqlite.sqlite-jdbc.version"))
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

    private String getDriverVersionOrThrow(String type, String keyHint) {
        String sysKey = "sorekillteams." + type + ".driver.version";
        String v = System.getProperty(sysKey);
        if (v != null && !v.isBlank()) return v.trim();

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

            this.sqlInvites = null;

            stopSqlAutoRefreshTask();
            return;
        }

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

        this.sqlInvites = new SqlTeamInviteStorage(db);

        this.storage = new SqlTeamStorage(db);
        this.teams = new SimpleTeamService(this, storage);
        this.storageTypeActive = type;

        startSqlAutoRefreshTask();
    }

    private void stopSql() {
        if (sqlDb != null) {
            try { sqlDb.stop(); } catch (Exception ignored) {}
            sqlDb = null;
        }
        sqlDialect = null;
        sqlInvites = null;
    }

    private void syncHomesWiringFromConfig(boolean isStartup) {
        boolean homesEnabled = getConfig().getBoolean("homes.enabled", false);

        if (!homesEnabled) {
            teamHomeStorage = null;
            teamHomes = null;
            return;
        }

        if (teamHomes == null) {
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

    public void loadHomesBestEffort(String phase) {
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
                    lastSnapshotRefreshMs = System.currentTimeMillis();
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
            UUID sqlTeamId;
            Team loadedTeam;

            try {
                sqlTeamId = sqlStorage.findTeamIdForMember(playerUuid);

                loadedTeam = null;
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

    public void startSqlAutoRefreshTask() {
        stopSqlAutoRefreshTask();

        if ("yaml".equalsIgnoreCase(storageTypeActive)) return;

        long periodTicks = Math.max(20L, getConfig().getLong("storage.sql_auto_refresh_period_ticks", 40L));

        sqlRefreshTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                ensureTeamsSnapshotFreshFromSql();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p == null) continue;
                    ensureTeamFreshFromSql(p.getUniqueId());
                }
            } catch (Throwable t) {
                getLogger().warning("SQL auto-refresh task error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, periodTicks, periodTicks).getTaskId();
    }

    private void stopSqlAutoRefreshTask() {
        if (sqlRefreshTaskId < 0) return;
        stopTask(sqlRefreshTaskId);
        sqlRefreshTaskId = -1;
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
                () -> {
                    long now = System.currentTimeMillis();

                    invites.purgeExpiredAll(now);

                    if (!"yaml".equalsIgnoreCase(storageTypeActive) && sqlInvites != null) {
                        try {
                            sqlInvites.purgeExpired(now);
                        } catch (Exception e) {
                            getLogger().warning("SQL invite purge failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                },
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
            if (hs != null && hv != null) {
                if (hv instanceof net.chumbucket.sorekillteams.service.SimpleTeamHomeService shs) {
                    if (shs.isDirty()) {
                        hs.saveAll(hv);
                    }
                } else {
                    hs.saveAll(hv);
                }
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
            if (hs != null && hv != null) {
                if (hv instanceof net.chumbucket.sorekillteams.service.SimpleTeamHomeService shs) {
                    if (shs.isDirty()) {
                        hs.saveAll(hv);
                    }
                } else {
                    hs.saveAll(hv);
                }
            }

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

    public SqlTeamInviteStorage sqlInvites() { return sqlInvites; }

    public UpdateChecker updateChecker() { return updateChecker; }

    public TeamHomeService teamHomes() { return teamHomes; }
    public TeamHomeStorage teamHomeStorage() { return teamHomeStorage; }

    public RedisPresenceBus presenceBus() { return presenceBus; }

    public boolean isFriendlyFireToggleEnabled() {
        return getConfig().getBoolean("friendly_fire.toggle_enabled", true);
    }

    public boolean isTeamChatToggleEnabled() {
        return getConfig().getBoolean("chat.toggle_enabled", true);
    }

    public boolean isTeamChatEnabled() {
        return getConfig().getBoolean("chat.enabled", true);
    }

    public boolean isTeamChatNetworkEnabled() {
        if (!getConfig().getBoolean("redis.enabled", false)) return false;
        return teamChatBus != null && teamChatBus.isRunning();
    }

    public String storageTypeActive() {
        return storageTypeActive;
    }

    public SqlDialect sqlDialect() {
        return sqlDialect;
    }
}
