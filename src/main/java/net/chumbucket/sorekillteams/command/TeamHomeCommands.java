package net.chumbucket.sorekillteams.command;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamHome;
import net.chumbucket.sorekillteams.network.TeamEventPacket;
import net.chumbucket.sorekillteams.network.TeamHomeTeleportPacket;
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

    private static final String HOME_BYPASS_COOLDOWN_PERMISSION = "sorekillteams.home.bypasscooldown";
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

    private void publishHomeEvent(Team team, Player actor, TeamEventPacket.Type type, String homeDisplay) {
        if (team == null || actor == null || type == null) return;

        plugin.publishTeamEvent(new TeamEventPacket(
                plugin.networkServerName(),
                type,
                team.getId(),
                team.getName(),
                actor.getUniqueId(),
                actor.getName(),
                null,
                (homeDisplay == null ? "" : homeDisplay),
                System.currentTimeMillis()
        ));
    }

    private void refreshTeamMenus(UUID teamId) {
        if (teamId == null) return;
        if (plugin.menuRouter() != null) {
            plugin.menuRouter().refreshTeamMenusForLocalViewers(teamId);
        }
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

        refreshTeamMenus(team.getId());
        publishHomeEvent(team, p, TeamEventPacket.Type.HOME_SET, raw);

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

        refreshTeamMenus(team.getId());
        publishHomeEvent(team, p, TeamEventPacket.Type.HOME_DELETED, raw);

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
                    "{home}", safeHomeName(h),
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
                    .map(TeamHomeCommands::safeHomeName)
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

    private void completeTeleport(Player p, UUID teamId, TeamHome hh, boolean bypassCooldown) {
        if (p == null || !p.isOnline()) return;

        if (hh == null) {
            plugin.msg().send(p, "team_home_not_found", "{home}", "home");
            return;
        }

        if (!passesProxyRestriction(p, hh)) return;

        teleportNow(p, teamId, hh, bypassCooldown);
    }

    private boolean teleportNow(Player p, UUID teamId, TeamHome h, boolean bypassCooldown) {
        if (h == null) return true;

        if (shouldRouteCrossServer(h)) {
            return routeCrossServerTeleport_WithPluginMessaging(p, teamId, h, bypassCooldown);
        }

        Location dest = h.toLocationOrNull();
        if (dest == null) {
            plugin.msg().send(p, "team_home_world_missing", "{home}", safeHomeName(h));
            return true;
        }

        p.teleport(dest);

        if (!bypassCooldown) {
            cooldowns.markTeleported(p);
        }

        p.playSound(p.getLocation(), TELEPORT_SOUND, 1.0f, 1.0f);
        plugin.msg().send(p, "team_home_teleported", "{home}", safeHomeName(h));
        return true;
    }

    private boolean shouldRouteCrossServer(TeamHome h) {
        boolean proxyMode = plugin.getConfig().getBoolean("homes.proxy_mode", false);
        if (!proxyMode) return false;

        boolean restrict = plugin.getConfig().getBoolean("homes.restrict_to_same_server", true);
        if (restrict) return false;

        String target = (h == null ? null : h.getServerName());
        if (target == null || target.isBlank()) return false;

        String current = plugin.getConfig().getString("homes.server_name", "default");
        if (current == null || current.isBlank()) current = "default";

        return !current.equalsIgnoreCase(target);
    }

    /**
     * Cross-server route:
     * - Publish Redis teleport request (destination server stores it as "pending")
     * - Use plugin messaging to ask the proxy to connect the player to the destination server
     */
    private boolean routeCrossServerTeleport_WithPluginMessaging(Player p, UUID teamId, TeamHome h, boolean bypassCooldown) {
        if (plugin.teamHomeBus() == null || !plugin.teamHomeBus().isRunning()) {
            plugin.msg().send(p, "team_home_proxy_unavailable");
            return true;
        }

        String targetServer = h.getServerName();
        if (targetServer == null || targetServer.isBlank()) {
            plugin.msg().send(p, "team_home_proxy_unavailable");
            return true;
        }

        if (!bypassCooldown) {
            cooldowns.markTeleported(p);
        }

        TeamHomeTeleportPacket pkt = new TeamHomeTeleportPacket(
                plugin.networkServerName(),
                targetServer,
                teamId,
                (h.getName() == null ? "" : h.getName()),
                safeHomeName(h),
                p.getUniqueId(),
                p.getName(),
                UUID.randomUUID().toString().substring(0, 8),
                System.currentTimeMillis()
        );

        plugin.teamHomeBus().publish(pkt);

        // ✅ This is plugin messaging (BungeeCord / velocity:main)
        plugin.requestServerSwitchBestEffort(p, targetServer);

        plugin.msg().send(p, "team_home_switching_server",
                "{server}", targetServer,
                "{home}", safeHomeName(h)
        );

        return true;
    }

    private boolean passesProxyRestriction(Player p, TeamHome h) {
        boolean proxyMode = plugin.getConfig().getBoolean("homes.proxy_mode", false);
        boolean restrict = plugin.getConfig().getBoolean("homes.restrict_to_same_server", true);
        if (!proxyMode || !restrict) return true;

        String currentServer = plugin.getConfig().getString("homes.server_name", "default");
        if (currentServer != null && h != null && h.getServerName() != null
                && !currentServer.equalsIgnoreCase(h.getServerName())) {

            plugin.msg().send(p, "team_home_wrong_server",
                    "{home}", safeHomeName(h),
                    "{server}", h.getServerName()
            );
            return false;
        }
        return true;
    }

    private static String safeHomeName(TeamHome h) {
        if (h == null) return "home";
        String d = h.getDisplayName();
        if (d != null && !d.isBlank()) return d;
        String k = h.getName();
        return (k == null || k.isBlank()) ? "home" : k;
    }
}
