/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.command;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamHome;
import net.chumbucket.sorekillteams.model.TeamInvite;
import net.chumbucket.sorekillteams.service.TeamHomeService;
import net.chumbucket.sorekillteams.service.TeamServiceException;
import net.chumbucket.sorekillteams.util.CommandErrors;
import net.chumbucket.sorekillteams.util.Msg;
import net.chumbucket.sorekillteams.util.TeamNameValidator;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public final class TeamCommand implements CommandExecutor, Listener {

    private final SorekillTeamsPlugin plugin;
    private final TeamNameValidator nameValidator;

    private static final DateTimeFormatter TEAM_CREATED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    private static final String SPY_PERMISSION = "sorekillteams.spy";

    // Cooldown bypass for /team home
    private static final String HOME_BYPASS_COOLDOWN_PERMISSION = "sorekillteams.home.bypasscooldown";

    // Warmup bypass (optional)
    private static final String HOME_BYPASS_WARMUP_PERMISSION = "sorekillteams.home.bypasswarmup";

    // Per-player cooldown tracking (NOT team-wide)
    private final Map<UUID, Long> lastHomeTeleportMsByPlayer = new HashMap<>();

    // Track running warmups per player so countdowns don't stack
    private final Map<UUID, WarmupSession> warmupByPlayer = new HashMap<>();

    // Sounds (match your chosen HuskHomes-like behavior)
    private static final Sound WARMUP_TICK_SOUND = Sound.BLOCK_NOTE_BLOCK_HAT;
    private static final Sound CANCEL_SOUND = Sound.ENTITY_ITEM_BREAK;
    private static final Sound TELEPORT_SOUND = Sound.ENTITY_ENDERMAN_TELEPORT;

    public TeamCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.nameValidator = new TeamNameValidator(plugin);

        // Register listener so hit-cancel works
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg().send(sender, "player_only");
            return true;
        }

        if (!p.hasPermission("sorekillteams.use")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        if (args.length == 0) {
            plugin.msg().send(p, "team_usage");
            return true;
        }

        final boolean debug = plugin.debug() != null && plugin.debug().enabled();
        final String sub = args[0].toLowerCase(Locale.ROOT);

        try {
            switch (sub) {

                // =========================
                // 1.1.2: /team reload
                // =========================
                case "reload" -> {
                    if (!p.hasPermission("sorekillteams.reload") && !p.hasPermission("sorekillteams.admin")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    plugin.reloadEverything();
                    plugin.msg().send(p, "plugin_reloaded");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " ran /team reload");
                    return true;
                }

                // =========================
                // 1.1.0: /team chat ...
                // =========================
                case "chat", "tc", "teamchat" -> {
                    if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
                        plugin.msg().send(p, "teamchat_disabled");
                        return true;
                    }
                    if (!plugin.getConfig().getBoolean("chat.toggle_enabled", true)) {
                        plugin.msg().send(p, "teamchat_toggle_disabled");
                        return true;
                    }
                    if (!p.hasPermission("sorekillteams.teamchat")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    if (args.length < 2) {
                        boolean newState = plugin.teams().toggleTeamChat(p.getUniqueId());
                        plugin.msg().send(p, newState ? "teamchat_on" : "teamchat_off");

                        if (debug) {
                            plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " teamchat toggle -> " + (newState ? "ON" : "OFF"));
                        }
                        return true;
                    }

                    String mode = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
                    switch (mode) {
                        case "on", "enable", "enabled", "true" -> {
                            plugin.teams().setTeamChatEnabled(p.getUniqueId(), true);
                            plugin.msg().send(p, "teamchat_on");
                            if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " teamchat -> ON");
                            return true;
                        }
                        case "off", "disable", "disabled", "false" -> {
                            plugin.teams().setTeamChatEnabled(p.getUniqueId(), false);
                            plugin.msg().send(p, "teamchat_off");
                            if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " teamchat -> OFF");
                            return true;
                        }
                        case "toggle" -> {
                            boolean newState = plugin.teams().toggleTeamChat(p.getUniqueId());
                            plugin.msg().send(p, newState ? "teamchat_on" : "teamchat_off");
                            if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " teamchat toggle -> " + (newState ? "ON" : "OFF"));
                            return true;
                        }
                        case "status" -> {
                            boolean on = plugin.teams().isTeamChatEnabled(p.getUniqueId());
                            plugin.msg().send(p, on ? "teamchat_on" : "teamchat_off");
                            return true;
                        }
                        default -> {
                            plugin.msg().send(p, "teamchat_toggle_disabled");
                            return true;
                        }
                    }
                }

                // =========================
                // 1.0.8: /team spy ...
                // =========================
                case "spy" -> {
                    if (!p.hasPermission(SPY_PERMISSION)) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    if (!plugin.getConfig().getBoolean("chat.spy.enabled", true)) {
                        plugin.msg().send(p, "team_spy_disabled");
                        return true;
                    }

                    if (args.length < 2) {
                        plugin.msg().send(p, "team_spy_usage");
                        return true;
                    }

                    String arg1 = args[1] == null ? "" : args[1].trim();

                    if (arg1.equalsIgnoreCase("list")) {
                        Collection<Team> spied = plugin.teams().getSpiedTeams(p.getUniqueId());
                        if (spied == null || spied.isEmpty()) {
                            plugin.msg().send(p, "team_spy_list_empty");
                            return true;
                        }

                        String joined = spied.stream()
                                .filter(Objects::nonNull)
                                .map(Team::getName)
                                .filter(n -> n != null && !n.isBlank())
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .collect(Collectors.joining(Msg.color("&7, &c")));

                        plugin.msg().send(p, "team_spy_list",
                                "{teams}", Msg.color("&c" + joined)
                        );
                        return true;
                    }

                    if (arg1.equalsIgnoreCase("off") || arg1.equalsIgnoreCase("clear")) {
                        plugin.teams().clearSpy(p.getUniqueId());
                        plugin.msg().send(p, "team_spy_cleared");
                        return true;
                    }

                    String teamNameRaw = joinArgsAfter(args, 0);
                    if (teamNameRaw.isBlank()) {
                        plugin.msg().send(p, "team_spy_usage");
                        return true;
                    }

                    Team team = plugin.teams().getTeamByName(teamNameRaw).orElse(null);
                    if (team == null) {
                        plugin.msg().send(p, "team_spy_team_not_found",
                                "{team}", teamNameRaw
                        );
                        return true;
                    }

                    boolean enabled = plugin.teams().toggleSpy(p.getUniqueId(), team.getId());
                    if (enabled) {
                        plugin.msg().send(p, "team_spy_on",
                                "{team}", Msg.color(team.getName())
                        );
                    } else {
                        plugin.msg().send(p, "team_spy_off",
                                "{team}", Msg.color(team.getName())
                        );
                    }

                    if (debug) {
                        plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " spy toggled team=" + team.getName() + " -> " + (enabled ? "ON" : "OFF"));
                    }
                    return true;
                }

                // =========================
                // Team homes (team-owned)
                // =========================
                case "sethome" -> {
                    if (!p.hasPermission("sorekillteams.sethome")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (!plugin.getConfig().getBoolean("homes.enabled", false) || plugin.teamHomes() == null) {
                        plugin.msg().send(p, "homes_disabled");
                        return true;
                    }

                    Team team = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (team == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }
                    if (!p.getUniqueId().equals(team.getOwner())) {
                        plugin.msg().send(p, "team_home_owner_only");
                        return true;
                    }

                    if (args.length < 2) {
                        plugin.msg().send(p, "team_sethome_usage");
                        return true;
                    }

                    String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                    String key = normalizeHomeName(raw);
                    if (key.isBlank()) {
                        plugin.msg().send(p, "team_sethome_usage");
                        return true;
                    }

                    int maxHomes = Math.max(1, plugin.getConfig().getInt("homes.max_homes", 1));

                    Location loc = p.getLocation();
                    String serverName = plugin.getConfig().getString("homes.server_name", "default");

                    TeamHome home = new TeamHome(
                            team.getId(),
                            key,
                            raw,
                            (loc.getWorld() != null ? loc.getWorld().getName() : ""),
                            loc.getX(), loc.getY(), loc.getZ(),
                            loc.getYaw(), loc.getPitch(),
                            System.currentTimeMillis(),
                            p.getUniqueId(),
                            serverName
                    );

                    boolean ok = plugin.teamHomes().setHome(home, maxHomes);
                    if (!ok) {
                        plugin.msg().send(p, "team_home_max_reached", "{max}", String.valueOf(maxHomes));
                        return true;
                    }

                    try {
                        if (plugin.teamHomeStorage() != null && plugin.teamHomes() != null) {
                            plugin.teamHomeStorage().saveAll(plugin.teamHomes());
                        }
                    } catch (Exception ignored) {}

                    plugin.msg().send(p, "team_home_set", "{home}", raw);

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " set team home=" + raw + " team=" + team.getName());
                    return true;
                }

                case "delhome" -> {
                    if (!p.hasPermission("sorekillteams.delhome")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (!plugin.getConfig().getBoolean("homes.enabled", false) || plugin.teamHomes() == null) {
                        plugin.msg().send(p, "homes_disabled");
                        return true;
                    }

                    Team team = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (team == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }
                    if (!p.getUniqueId().equals(team.getOwner())) {
                        plugin.msg().send(p, "team_home_owner_only");
                        return true;
                    }

                    if (args.length < 2) {
                        plugin.msg().send(p, "team_delhome_usage");
                        return true;
                    }

                    String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                    String key = normalizeHomeName(raw);
                    if (key.isBlank()) {
                        plugin.msg().send(p, "team_delhome_usage");
                        return true;
                    }

                    boolean ok = plugin.teamHomes().deleteHome(team.getId(), key);
                    if (!ok) {
                        plugin.msg().send(p, "team_home_not_found", "{home}", raw);
                        return true;
                    }

                    try {
                        if (plugin.teamHomeStorage() != null && plugin.teamHomes() != null) {
                            plugin.teamHomeStorage().saveAll(plugin.teamHomes());
                        }
                    } catch (Exception ignored) {}

                    plugin.msg().send(p, "team_home_deleted", "{home}", raw);

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " deleted team home=" + raw + " team=" + team.getName());
                    return true;
                }

                case "homes" -> {
                    if (!p.hasPermission("sorekillteams.homes")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (!plugin.getConfig().getBoolean("homes.enabled", false) || plugin.teamHomes() == null) {
                        plugin.msg().send(p, "homes_disabled");
                        return true;
                    }

                    Team team = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (team == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }

                    List<TeamHome> list = plugin.teamHomes().listHomes(team.getId());
                    if (list == null) list = List.of();
                    list = list.stream().filter(Objects::nonNull).toList();

                    if (list.isEmpty()) {
                        plugin.msg().send(p, "team_homes_none");
                        return true;
                    }

                    plugin.msg().send(p, "team_homes_header",
                            "{team}", Msg.color(team.getName())
                    );

                    for (TeamHome h : list) {
                        plugin.msg().send(p, "team_homes_entry",
                                "{home}", (h.getDisplayName() == null || h.getDisplayName().isBlank()) ? h.getName() : h.getDisplayName(),
                                "{world}", (h.getWorld() == null || h.getWorld().isBlank()) ? "world" : h.getWorld()
                        );
                    }

                    return true;
                }

                case "home" -> {
                    if (!p.hasPermission("sorekillteams.home")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (!plugin.getConfig().getBoolean("homes.enabled", false) || plugin.teamHomes() == null) {
                        plugin.msg().send(p, "homes_disabled");
                        return true;
                    }

                    Team team = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (team == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }

                    TeamHomeService hs = plugin.teamHomes();
                    List<TeamHome> homes = hs.listHomes(team.getId());
                    if (homes == null) homes = List.of();

                    // ✅ Fix: remove possible nulls so homes.get(0) is never null (eliminates IDE NPE warning)
                    homes = homes.stream().filter(Objects::nonNull).toList();

                    if (homes.isEmpty()) {
                        plugin.msg().send(p, "team_homes_none");
                        return true;
                    }

                    boolean bypassCooldown = p.hasPermission(HOME_BYPASS_COOLDOWN_PERMISSION);
                    boolean bypassWarmup = p.hasPermission(HOME_BYPASS_WARMUP_PERMISSION);

                    // /team home (no name)
                    if (args.length < 2) {
                        if (homes.size() == 1) {
                            TeamHome only = homes.get(0);

                            String key = only.getName();
                            if (key == null || key.isBlank()) {
                                plugin.msg().send(p, "team_home_usage");
                                return true;
                            }

                            if (!passesProxyRestriction(p, only)) return true;

                            if (!bypassCooldown && !passesPlayerCooldown(p)) return true;

                            int warmupSeconds = Math.max(0, plugin.getConfig().getInt("homes.warmup_seconds", 0));
                            if (!bypassWarmup && warmupSeconds > 0) {
                                startActionbarWarmupTeleport(p, team.getId(), key, warmupSeconds, () -> hs.getHome(team.getId(), key).orElse(null));
                                return true;
                            }

                            Location dest = only.toLocationOrNull();
                            if (dest == null) {
                                plugin.msg().send(p, "team_home_world_missing", "{home}", only.getDisplayName());
                                return true;
                            }

                            p.teleport(dest);
                            markPlayerTeleportedNow(p);
                            p.playSound(p.getLocation(), TELEPORT_SOUND, 1.0f, 1.0f);
                            plugin.msg().send(p, "team_home_teleported", "{home}", only.getDisplayName());
                            return true;
                        }

                        String list = homes.stream()
                                .map(h -> (h.getDisplayName() == null || h.getDisplayName().isBlank()) ? h.getName() : h.getDisplayName())
                                .filter(n -> n != null && !n.isBlank())
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .collect(Collectors.joining(Msg.color("&7, &f")));

                        if (!trySendMsg(p, "team_home_multiple", "{homes}", list)) {
                            p.sendMessage(plugin.msg().prefix() + Msg.color("&7Multiple team homes set. Use &f/team home <name>&7. Homes: &f" + list));
                        }
                        return true;
                    }

                    // /team home <name>
                    String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                    String key = normalizeHomeName(raw);
                    if (key.isBlank()) {
                        plugin.msg().send(p, "team_home_usage");
                        return true;
                    }

                    TeamHome h = hs.getHome(team.getId(), key).orElse(null);
                    if (h == null) {
                        plugin.msg().send(p, "team_home_not_found", "{home}", raw);
                        return true;
                    }

                    if (!passesProxyRestriction(p, h)) return true;

                    if (!bypassCooldown && !passesPlayerCooldown(p)) return true;

                    int warmupSeconds = Math.max(0, plugin.getConfig().getInt("homes.warmup_seconds", 0));
                    if (!bypassWarmup && warmupSeconds > 0) {
                        startActionbarWarmupTeleport(p, team.getId(), key, warmupSeconds, () -> hs.getHome(team.getId(), key).orElse(null));
                        return true;
                    }

                    Location dest = h.toLocationOrNull();
                    if (dest == null) {
                        plugin.msg().send(p, "team_home_world_missing", "{home}", h.getDisplayName());
                        return true;
                    }

                    p.teleport(dest);
                    markPlayerTeleportedNow(p);
                    p.playSound(p.getLocation(), TELEPORT_SOUND, 1.0f, 1.0f);
                    plugin.msg().send(p, "team_home_teleported", "{home}", h.getDisplayName());
                    return true;
                }

                // =========================
                // create
                // =========================
                case "create" -> {
                    if (!p.hasPermission("sorekillteams.create")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_create_usage");
                        return true;
                    }

                    String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    TeamNameValidator.Validation v = nameValidator.validate(rawName);
                    if (!v.ok()) {
                        plugin.msg().send(p, v.reasonKey());
                        return true;
                    }

                    Team t = plugin.teams().createTeam(p.getUniqueId(), v.plainName());

                    plugin.msg().send(p, "team_created",
                            "{team}", Msg.color(t.getName())
                    );

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " created team=" + t.getName());
                    return true;
                }

                // =========================
                // invite
                // =========================
                case "invite" -> {
                    if (!p.hasPermission("sorekillteams.invite")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_invite_usage");
                        return true;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        plugin.msg().send(p, "team_invite_player_offline");
                        return true;
                    }

                    plugin.teams().invite(p.getUniqueId(), target.getUniqueId());

                    String teamName = plugin.teams().getTeamByPlayer(p.getUniqueId())
                            .map(Team::getName)
                            .orElse("Team");

                    plugin.msg().send(p, "team_invite_sent",
                            "{target}", target.getName(),
                            "{team}", Msg.color(teamName)
                    );

                    plugin.msg().send(target, "team_invite_received",
                            "{inviter}", p.getName(),
                            "{team}", Msg.color(teamName)
                    );

                    plugin.msg().send(target, "team_usage");
                    plugin.msg().send(target, "team_invites_tip");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " invited " + target.getName() + " team=" + teamName);
                    return true;
                }

                // =========================
                // invites (list)
                // =========================
                case "invites" -> {
                    if (!p.hasPermission("sorekillteams.invites")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Collection<TeamInvite> invs = plugin.teams().getInvites(p.getUniqueId());
                    if (invs == null || invs.isEmpty()) {
                        plugin.msg().send(p, "team_invites_none");
                        return true;
                    }

                    List<TeamInvite> sorted = invs.stream()
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparingLong(TeamInvite::getExpiresAtMs))
                            .toList();

                    plugin.msg().send(p, "team_invites_header");

                    long now = System.currentTimeMillis();
                    for (TeamInvite inv : sorted) {
                        String teamName = plugin.teams().getTeamById(inv.getTeamId())
                                .map(Team::getName)
                                .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

                        String inviterName = nameOf(inv.getInviter());
                        long secondsLeft = inv.getSecondsRemaining(now);

                        plugin.msg().send(p, "team_invites_entry",
                                "{team}", Msg.color(teamName),
                                "{inviter}", inviterName,
                                "{seconds}", String.valueOf(secondsLeft)
                        );
                    }

                    if (sorted.size() > 1) {
                        plugin.msg().send(p, "team_invites_tip");
                    }

                    return true;
                }

                // =========================
                // accept
                // =========================
                case "accept" -> {
                    if (!p.hasPermission("sorekillteams.accept")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Optional<UUID> teamId = Optional.empty();
                    if (args.length >= 2) {
                        String teamArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        teamId = resolveInviteTeamIdByName(p.getUniqueId(), teamArg);
                        if (teamId.isEmpty()) {
                            plugin.msg().send(p, "team_invite_no_match");
                            return true;
                        }
                    }

                    Optional<TeamInvite> invOpt = plugin.teams().acceptInvite(p.getUniqueId(), teamId);
                    if (invOpt.isEmpty()) {
                        plugin.msg().send(p, "team_no_invites");
                        return true;
                    }

                    TeamInvite inv = invOpt.get();

                    String teamName = plugin.teams().getTeamById(inv.getTeamId())
                            .map(Team::getName)
                            .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

                    String inviterName = nameOf(inv.getInviter());

                    plugin.msg().send(p, "team_joined",
                            "{team}", Msg.color(teamName)
                    );
                    plugin.msg().send(p, "team_joined_who",
                            "{inviter}", inviterName
                    );

                    Player inviterOnline = Bukkit.getPlayer(inv.getInviter());
                    if (inviterOnline != null) {
                        plugin.msg().send(inviterOnline, "team_invite_accepted_inviter",
                                "{player}", p.getName(),
                                "{team}", Msg.color(teamName)
                        );
                    }

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " accepted invite team=" + teamName);
                    return true;
                }

                // =========================
                // deny
                // =========================
                case "deny" -> {
                    if (!p.hasPermission("sorekillteams.deny")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Optional<UUID> teamId = Optional.empty();
                    if (args.length >= 2) {
                        String teamArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        teamId = resolveInviteTeamIdByName(p.getUniqueId(), teamArg);
                        if (teamId.isEmpty()) {
                            plugin.msg().send(p, "team_invite_no_match");
                            return true;
                        }
                    }

                    boolean ok = plugin.teams().denyInvite(p.getUniqueId(), teamId);
                    if (!ok) {
                        plugin.msg().send(p, "team_no_invites");
                        return true;
                    }

                    plugin.msg().send(p, "team_invite_denied");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " denied invite");
                    return true;
                }

                // =========================
                // leave
                // =========================
                case "leave" -> {
                    if (!p.hasPermission("sorekillteams.leave")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    plugin.teams().leaveTeam(p.getUniqueId());
                    plugin.msg().send(p, "team_left");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " left team");
                    return true;
                }

                // =========================
                // disband
                // =========================
                case "disband" -> {
                    if (!p.hasPermission("sorekillteams.disband")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    plugin.teams().disbandTeam(p.getUniqueId());

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " disbanded team");
                    return true;
                }

                // =========================
                // rename
                // =========================
                case "rename" -> {
                    if (!p.hasPermission("sorekillteams.rename")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_rename_usage");
                        return true;
                    }

                    String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    TeamNameValidator.Validation v = nameValidator.validate(rawName);
                    if (!v.ok()) {
                        plugin.msg().send(p, v.reasonKey());
                        return true;
                    }

                    Team before = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    String oldName = before != null ? before.getName() : "Team";

                    plugin.teams().renameTeam(p.getUniqueId(), v.plainName());

                    Team after = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    String newName = after != null ? after.getName() : v.plainName();

                    plugin.msg().send(p, "team_renamed",
                            "{old}", Msg.color(oldName),
                            "{team}", Msg.color(newName)
                    );

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " renamed team old=" + oldName + " new=" + newName);
                    return true;
                }

                // =========================
                // kick
                // =========================
                case "kick" -> {
                    if (!p.hasPermission("sorekillteams.kick")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_kick_usage");
                        return true;
                    }

                    UUID targetUuid = resolvePlayerUuidOnlineOrUuid(args[1]);
                    if (targetUuid == null) {
                        plugin.msg().send(p, "team_player_must_be_online_or_uuid");
                        return true;
                    }

                    plugin.teams().kickMember(p.getUniqueId(), targetUuid);

                    String targetName = nameOf(targetUuid);
                    Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    String teamName = (t != null ? t.getName() : "Team");

                    plugin.msg().send(p, "team_kick_success",
                            "{player}", targetName,
                            "{team}", Msg.color(teamName)
                    );

                    Player targetOnline = Bukkit.getPlayer(targetUuid);
                    if (targetOnline != null) {
                        plugin.msg().send(targetOnline, "team_kick_target",
                                "{team}", Msg.color(teamName),
                                "{by}", p.getName()
                        );
                    }

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " kicked uuid=" + targetUuid);
                    return true;
                }

                // =========================
                // transfer
                // =========================
                case "transfer" -> {
                    if (!p.hasPermission("sorekillteams.transfer")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        plugin.msg().send(p, "team_transfer_usage");
                        return true;
                    }

                    UUID targetUuid = resolvePlayerUuidOnlineOrUuid(args[1]);
                    if (targetUuid == null) {
                        plugin.msg().send(p, "team_player_must_be_online_or_uuid");
                        return true;
                    }

                    plugin.teams().transferOwnership(p.getUniqueId(), targetUuid);

                    String targetName = nameOf(targetUuid);
                    Team t = plugin.teams().getTeamByPlayer(targetUuid).orElse(null);
                    String teamName = (t != null ? t.getName() : "Team");

                    plugin.msg().send(p, "team_transfer_success",
                            "{player}", targetName,
                            "{team}", Msg.color(teamName)
                    );

                    Player targetOnline = Bukkit.getPlayer(targetUuid);
                    if (targetOnline != null) {
                        plugin.msg().send(targetOnline, "team_transfer_received",
                                "{team}", Msg.color(teamName),
                                "{by}", p.getName()
                        );
                    }

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " transferred ownership -> " + targetUuid);
                    return true;
                }

                // =========================
                // info
                // =========================
                case "info" -> {
                    if (!p.hasPermission("sorekillteams.info")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (t == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }

                    String ownerName = nameOf(t.getOwner());

                    String members = t.getMembers().stream()
                            .filter(Objects::nonNull)
                            .map(uuid -> {
                                Player online = Bukkit.getPlayer(uuid);
                                if (online != null) return Msg.color("&a" + online.getName());

                                OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
                                String n = (off != null && off.getName() != null && !off.getName().isBlank())
                                        ? off.getName()
                                        : uuid.toString().substring(0, 8);
                                return Msg.color("&c" + n);
                            })
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.joining(Msg.color("&7, ")));

                    String created = TEAM_CREATED_FMT.format(Instant.ofEpochMilli(t.getCreatedAtMs()));

                    boolean tc = plugin.teams().isTeamChatEnabled(p.getUniqueId());
                    String tcState = tc ? "&aON" : "&cOFF";
                    String ffState = t.isFriendlyFireEnabled() ? "&aON" : "&cOFF";

                    plugin.msg().send(p, "team_info_header");
                    plugin.msg().send(p, "team_info_name", "{team}", Msg.color(t.getName()));
                    plugin.msg().send(p, "team_info_owner", "{owner}", ownerName);
                    plugin.msg().send(p, "team_info_members",
                            "{count}", String.valueOf(t.getMembers().size()),
                            "{members}", members
                    );

                    plugin.msg().send(p, "team_info_legend");
                    plugin.msg().send(p, "team_info_created", "{date}", created);
                    plugin.msg().send(p, "team_info_tc", "{state}", Msg.color(tcState));
                    plugin.msg().send(p, "team_info_ff", "{state}", Msg.color(ffState));

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " viewed team info team=" + t.getName());
                    return true;
                }

                // =========================
                // ff / friendlyfire
                // =========================
                case "ff", "friendlyfire" -> {
                    if (!p.hasPermission("sorekillteams.ff")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }

                    Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
                    if (t == null) {
                        plugin.msg().send(p, "team_not_in_team");
                        return true;
                    }

                    if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
                        String state = t.isFriendlyFireEnabled() ? "&aON" : "&cOFF";
                        plugin.msg().send(p, "team_ff_status", "{state}", Msg.color(state));
                        plugin.msg().send(p, "team_ff_usage");
                        return true;
                    }

                    if (!t.getOwner().equals(p.getUniqueId())) {
                        plugin.msg().send(p, "team_not_owner");
                        return true;
                    }

                    String mode = args[1].toLowerCase(Locale.ROOT);
                    boolean newValue;

                    switch (mode) {
                        case "on", "true", "enable", "enabled" -> newValue = true;
                        case "off", "false", "disable", "disabled" -> newValue = false;
                        case "toggle" -> newValue = !t.isFriendlyFireEnabled();
                        default -> {
                            plugin.msg().send(p, "team_ff_usage");
                            return true;
                        }
                    }

                    t.setFriendlyFireEnabled(newValue);

                    try {
                        if (plugin.storage() != null && plugin.teams() != null) {
                            plugin.storage().saveAll(plugin.teams());
                        }
                    } catch (Exception ignored) {}

                    plugin.msg().send(p, newValue ? "team_ff_status_on" : "team_ff_status_off");

                    if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " ff -> " + (newValue ? "ON" : "OFF"));
                    return true;
                }

                default -> {
                    plugin.msg().send(p, "unknown_command");
                    plugin.msg().send(p, "team_usage");
                    return true;
                }
            }
        } catch (TeamServiceException ex) {
            CommandErrors.send(p, plugin, ex);

            if (debug) {
                plugin.getLogger().info("[TEAM-DBG] TeamServiceException sub=" + sub + " player=" + p.getName() + " code=" +
                        (ex.code() == null ? "null" : ex.code().name()));
            }
            return true;

        } catch (Exception ex) {
            p.sendMessage(plugin.msg().prefix() + "An error occurred.");
            plugin.getLogger().severe("Command error (" + sub + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return true;
        }
    }

    // ============================================================
    // Warmup cancel on hit
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;

        WarmupSession session = warmupByPlayer.get(p.getUniqueId());
        if (session == null) return;

        cancelWarmup(p.getUniqueId());

        // actionbar cancel + sound
        sendActionbar(p, "actionbar.team_home_cancelled_move");
        p.playSound(p.getLocation(), CANCEL_SOUND, 1.0f, 1.0f);

        // chat cancel
        plugin.msg().send(p, "team_home_cancelled_move");

        // clear the bar shortly after
        Bukkit.getScheduler().runTaskLater(plugin, () -> clearActionbar(p), 30L);
    }

    // ============================================================
    // ACTIONBAR warmup countdown + teleport (cancel on move/hit)
    // ============================================================

    @FunctionalInterface
    private interface HomeSupplier {
        TeamHome get();
    }

    private static final class WarmupSession {
        final int taskId;
        final Location startLoc;

        WarmupSession(int taskId, Location startLoc) {
            this.taskId = taskId;
            this.startLoc = startLoc;
        }
    }

    private void startActionbarWarmupTeleport(Player p,
                                              UUID teamId,
                                              String homeKey,
                                              int warmupSeconds,
                                              HomeSupplier homeSupplier) {

        cancelWarmup(p.getUniqueId());

        final UUID playerId = p.getUniqueId();
        final Location start = p.getLocation().clone();
        final int[] remaining = new int[]{Math.max(1, warmupSeconds)};

        int taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player live = Bukkit.getPlayer(playerId);
            if (live == null || !live.isOnline()) {
                cancelWarmup(playerId);
                return;
            }

            // still in same team?
            Team currentTeam = plugin.teams().getTeamByPlayer(playerId).orElse(null);
            if (currentTeam == null || !teamId.equals(currentTeam.getId())) {
                clearActionbar(live);
                cancelWarmup(playerId);
                return;
            }

            // cancel if moved
            WarmupSession sess = warmupByPlayer.get(playerId);
            if (sess != null && hasMoved(sess.startLoc, live.getLocation())) {
                cancelWarmup(playerId);

                sendActionbar(live, "actionbar.team_home_cancelled_move");
                live.playSound(live.getLocation(), CANCEL_SOUND, 1.0f, 1.0f);

                plugin.msg().send(live, "team_home_cancelled_move");

                Bukkit.getScheduler().runTaskLater(plugin, () -> clearActionbar(live), 30L);
                return;
            }

            // show countdown
            sendActionbar(live, "actionbar.team_home_warmup", "{seconds}", String.valueOf(remaining[0]));

            // tick sound each second
            live.playSound(live.getLocation(), WARMUP_TICK_SOUND, 1.0f, 1.0f);

            // teleport when it reaches 0
            if (remaining[0] <= 0) {
                TeamHome hh = homeSupplier.get();
                if (hh == null) {
                    clearActionbar(live);
                    cancelWarmup(playerId);
                    return;
                }

                Location dest = hh.toLocationOrNull();
                if (dest == null) {
                    clearActionbar(live);
                    plugin.msg().send(live, "team_home_world_missing", "{home}", hh.getDisplayName());
                    cancelWarmup(playerId);
                    return;
                }

                live.teleport(dest);
                markPlayerTeleportedNow(live);

                live.playSound(live.getLocation(), TELEPORT_SOUND, 1.0f, 1.0f);

                clearActionbar(live);
                plugin.msg().send(live, "team_home_teleported", "{home}", hh.getDisplayName());

                cancelWarmup(playerId);
                return;
            }

            remaining[0]--;
        }, 0L, 20L).getTaskId();

        warmupByPlayer.put(playerId, new WarmupSession(taskId, start));
    }

    private void cancelWarmup(UUID playerId) {
        WarmupSession session = warmupByPlayer.remove(playerId);
        if (session != null) {
            plugin.getServer().getScheduler().cancelTask(session.taskId);
        }
    }

    private boolean hasMoved(Location start, Location now) {
        if (start == null || now == null) return true;
        if (start.getWorld() == null || now.getWorld() == null) return true;
        if (!start.getWorld().equals(now.getWorld())) return true;

        // Only cancel on positional movement (rotation doesn't matter)
        double dx = now.getX() - start.getX();
        double dy = now.getY() - start.getY();
        double dz = now.getZ() - start.getZ();
        double distSq = (dx * dx) + (dy * dy) + (dz * dz);

        // small tolerance for jitter
        return distSq > 0.0004; // ~0.02 blocks
    }

    private void clearActionbar(Player p) {
        if (p == null) return;
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
    }

    // ============================================================
    // Per-player cooldown helpers
    // ============================================================

    private boolean passesPlayerCooldown(Player p) {
        int cooldownSeconds = Math.max(0, plugin.getConfig().getInt("homes.cooldown_seconds", 0));
        if (cooldownSeconds <= 0) return true;

        long now = System.currentTimeMillis();
        long last = lastHomeTeleportMsByPlayer.getOrDefault(p.getUniqueId(), 0L);
        long waitMs = cooldownSeconds * 1000L;

        if (now - last < waitMs) {
            long remain = (waitMs - (now - last) + 999) / 1000;
            plugin.msg().send(p, "team_home_cooldown", "{seconds}", String.valueOf(remain));
            return false;
        }
        return true;
    }

    private void markPlayerTeleportedNow(Player p) {
        if (p == null) return;
        lastHomeTeleportMsByPlayer.put(p.getUniqueId(), System.currentTimeMillis());
    }

    // ============================================================
    // Proxy restriction helper
    // ============================================================

    private boolean passesProxyRestriction(Player p, TeamHome h) {
        boolean proxyMode = plugin.getConfig().getBoolean("homes.proxy_mode", false);
        boolean restrict = plugin.getConfig().getBoolean("homes.restrict_to_same_server", true);
        if (!proxyMode || !restrict) return true;

        String currentServer = plugin.getConfig().getString("homes.server_name", "default");
        if (currentServer != null && !currentServer.equalsIgnoreCase(h.getServerName())) {
            plugin.msg().send(p, "team_home_wrong_server",
                    "{home}", h.getDisplayName(),
                    "{server}", h.getServerName()
            );
            return false;
        }
        return true;
    }

    // ============================================================
    // Actionbar message helper (supports nested "actionbar.*")
    // ============================================================

    private void sendActionbar(Player p, String key, String... placeholders) {
        String text = resolveMessageString(key);
        if (text == null || text.isBlank()) return;

        String out = text;
        if (placeholders != null) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                String k = placeholders[i];
                String v = placeholders[i + 1];
                if (k != null && v != null) out = out.replace(k, v);
            }
        }

        out = out.replace("{prefix}", plugin.msg().prefix());
        String colored = Msg.color(out);

        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(colored));
    }

    /**
     * Best-effort: fetch a raw message string from your message manager.
     * Supports dotted keys like "actionbar.team_home_warmup" as well as a flat fallback.
     */
    private String resolveMessageString(String key) {
        if (key == null || key.isBlank()) return null;

        Object msg = plugin.msg();
        if (msg == null) return null;

        for (String methodName : List.of("get", "raw", "message", "resolve", "string")) {
            try {
                Method m = msg.getClass().getMethod(methodName, String.class);
                Object val = m.invoke(msg, key);
                if (val instanceof String s) return s;
            } catch (Exception ignored) {}
        }

        // fallback: actionbar.team_home_warmup -> actionbar_team_home_warmup
        String flat = key.replace('.', '_');
        for (String methodName : List.of("get", "raw", "message", "resolve", "string")) {
            try {
                Method m = msg.getClass().getMethod(methodName, String.class);
                Object val = m.invoke(msg, flat);
                if (val instanceof String s) return s;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private boolean trySendMsg(Player p, String key, String... placeholders) {
        try {
            plugin.msg().send(p, key, placeholders);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ============================================================

    private Optional<UUID> resolveInviteTeamIdByName(UUID invitee, String teamArgRaw) {
        String wanted = normalize(teamArgRaw);

        Collection<TeamInvite> invites = plugin.teams().getInvites(invitee);
        if (invites == null || invites.isEmpty()) return Optional.empty();

        for (TeamInvite inv : invites) {
            if (inv == null) continue;

            String teamName = plugin.teams().getTeamById(inv.getTeamId())
                    .map(Team::getName)
                    .orElse(inv.getTeamName() != null ? inv.getTeamName() : "Team");

            if (normalize(teamName).equalsIgnoreCase(wanted)) {
                return Optional.of(inv.getTeamId());
            }
        }
        return Optional.empty();
    }

    private String normalize(String s) {
        if (s == null) return "";
        String colored = Msg.color(s);
        String stripped = ChatColor.stripColor(colored);
        return stripped == null ? "" : stripped.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    private String normalizeHomeName(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();

        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();

        return uuid.toString().substring(0, 8);
    }

    private UUID resolvePlayerUuidOnlineOrUuid(String arg) {
        if (arg == null || arg.isBlank()) return null;

        try {
            return UUID.fromString(arg);
        } catch (IllegalArgumentException ignored) {}

        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) return online.getUniqueId();

        return null;
    }

    private String joinArgsAfter(String[] args, int indexOfSubcommand) {
        int start = Math.max(0, indexOfSubcommand + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (args[i] == null) continue;
            String s = args[i].trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString().trim();
    }
}