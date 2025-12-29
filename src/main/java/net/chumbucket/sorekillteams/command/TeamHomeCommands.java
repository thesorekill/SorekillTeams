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
import net.chumbucket.sorekillteams.service.TeamHomeService;
import net.chumbucket.sorekillteams.util.Msg;
import net.chumbucket.sorekillteams.util.TeamHomeCooldowns;
import net.chumbucket.sorekillteams.util.TeamHomeWarmupManager;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

import static net.chumbucket.sorekillteams.util.CommandUtil.normalizeHomeName;

public final class TeamHomeCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;
    private final TeamHomeCooldowns cooldowns;
    private final TeamHomeWarmupManager warmups;

    // Cooldown bypass for /team home
    private static final String HOME_BYPASS_COOLDOWN_PERMISSION = "sorekillteams.home.bypasscooldown";
    // Warmup bypass
    private static final String HOME_BYPASS_WARMUP_PERMISSION = "sorekillteams.home.bypasswarmup";

    private static final Sound TELEPORT_SOUND = Sound.ENTITY_ENDERMAN_TELEPORT;

    public TeamHomeCommands(SorekillTeamsPlugin plugin,
                            TeamHomeCooldowns cooldowns,
                            TeamHomeWarmupManager warmups) {
        this.plugin = plugin;
        this.cooldowns = cooldowns;
        this.warmups = warmups;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        return switch (sub) {
            case "sethome" -> handleSetHome(p, args, debug);
            case "delhome" -> handleDelHome(p, args, debug);
            case "homes" -> handleHomesList(p);
            case "home" -> handleHomeTeleport(p, args);
            default -> false;
        };
    }

    private boolean homesEnabled() {
        return plugin.getConfig().getBoolean("homes.enabled", false) && plugin.teamHomes() != null;
    }

    private boolean handleSetHome(Player p, String[] args, boolean debug) {
        if (!p.hasPermission("sorekillteams.sethome")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }
        if (!homesEnabled()) {
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save team homes: " +
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        plugin.msg().send(p, "team_home_set", "{home}", raw);

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " set team home=" + raw + " team=" + team.getName());
        return true;
    }

    private boolean handleDelHome(Player p, String[] args, boolean debug) {
        if (!p.hasPermission("sorekillteams.delhome")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }
        if (!homesEnabled()) {
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save team homes: " +
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        plugin.msg().send(p, "team_home_deleted", "{home}", raw);

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " deleted team home=" + raw + " team=" + team.getName());
        return true;
    }

    private boolean handleHomesList(Player p) {
        if (!p.hasPermission("sorekillteams.homes")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }
        if (!homesEnabled()) {
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

    private boolean handleHomeTeleport(Player p, String[] args) {
        if (!p.hasPermission("sorekillteams.home")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }
        if (!homesEnabled()) {
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
                if (!bypassCooldown && !cooldowns.passes(p)) return true;

                int warmupSeconds = Math.max(0, plugin.getConfig().getInt("homes.warmup_seconds", 0));
                if (!bypassWarmup && warmupSeconds > 0) {
                    warmups.start(
                            p,
                            team.getId(),
                            warmupSeconds,
                            () -> hs.getHome(team.getId(), key).orElse(null),
                            (TeamHome hh) -> completeTeleport(p, team.getId(), hh, bypassCooldown)
                    );
                    return true;
                }

                return teleportNow(p, team.getId(), only, bypassCooldown);
            }

            String list = homes.stream()
                    .map(h -> (h.getDisplayName() == null || h.getDisplayName().isBlank()) ? h.getName() : h.getDisplayName())
                    .filter(n -> n != null && !n.isBlank())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(Msg.color("&7, &f")));

            plugin.msg().send(p, "team_home_multiple", "{homes}", list);
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
        if (!bypassCooldown && !cooldowns.passes(p)) return true;

        int warmupSeconds = Math.max(0, plugin.getConfig().getInt("homes.warmup_seconds", 0));
        if (!bypassWarmup && warmupSeconds > 0) {
            warmups.start(
                    p,
                    team.getId(),
                    warmupSeconds,
                    () -> hs.getHome(team.getId(), key).orElse(null),
                    (TeamHome hh) -> completeTeleport(p, team.getId(), hh, bypassCooldown)
            );
            return true;
        }

        return teleportNow(p, team.getId(), h, bypassCooldown);
    }

    /**
     * Runs after warmup completes. Re-checks restrictions and teleports if possible.
     */
    private void completeTeleport(Player p, UUID teamId, TeamHome hh, boolean bypassCooldown) {
        if (p == null || !p.isOnline()) return;

        if (hh == null) {
            // Home disappeared during warmup
            plugin.msg().send(p, "team_home_not_found", "{home}", "home");
            return;
        }

        // Re-check proxy restriction in case data changed during warmup
        if (!passesProxyRestriction(p, hh)) return;

        // We already checked cooldown before starting warmup, so no need to re-check here.
        // But if you want to be extra strict, uncomment:
        // if (!bypassCooldown && !cooldowns.passes(p)) return;

        teleportNow(p, teamId, hh, bypassCooldown);
    }

    private boolean teleportNow(Player p, UUID teamId, TeamHome h, boolean bypassCooldown) {
        if (h == null) return true;

        Location dest = h.toLocationOrNull();
        if (dest == null) {
            plugin.msg().send(p, "team_home_world_missing", "{home}", h.getDisplayName());
            return true;
        }

        p.teleport(dest);

        if (!bypassCooldown) {
            cooldowns.markTeleported(p);
        }

        p.playSound(p.getLocation(), TELEPORT_SOUND, 1.0f, 1.0f);
        plugin.msg().send(p, "team_home_teleported", "{home}", h.getDisplayName());
        return true;
    }

    /**
     * Proxy restriction helper (moved out of warmup manager; command-level check).
     */
    private boolean passesProxyRestriction(Player p, TeamHome h) {
        boolean proxyMode = plugin.getConfig().getBoolean("homes.proxy_mode", false);
        boolean restrict = plugin.getConfig().getBoolean("homes.restrict_to_same_server", true);
        if (!proxyMode || !restrict) return true;

        String currentServer = plugin.getConfig().getString("homes.server_name", "default");
        if (currentServer != null && h != null && h.getServerName() != null
                && !currentServer.equalsIgnoreCase(h.getServerName())) {

            plugin.msg().send(p, "team_home_wrong_server",
                    "{home}", h.getDisplayName(),
                    "{server}", h.getServerName()
            );
            return false;
        }
        return true;
    }
}
