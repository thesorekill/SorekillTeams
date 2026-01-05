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
import net.chumbucket.sorekillteams.network.RedisTeamChatBus;
import net.chumbucket.sorekillteams.service.TeamServiceException;
import net.chumbucket.sorekillteams.util.CommandErrors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TeamChatCommand implements CommandExecutor {

    private static final String PERM_NEW = "sorekillteams.teamchat";
    private static final String PERM_LEGACY = "sorekillteams.chat"; // keep compatibility

    private final SorekillTeamsPlugin plugin;

    public TeamChatCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean hasTeamChatPerm(Player p) {
        return p.hasPermission(PERM_NEW) || p.hasPermission(PERM_LEGACY);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg().send(sender, "player_only");
            return true;
        }

        if (!hasTeamChatPerm(p)) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            plugin.msg().send(p, "teamchat_disabled");
            return true;
        }

        final boolean debug = plugin.getConfig().getBoolean("chat.debug", false);

        try {
            // One-off message: /tc <message...>
            if (args.length > 0) {
                final String msg = String.join(" ", args).trim();
                if (msg.isEmpty()) return true;

                if (debug) {
                    plugin.getLogger().info("[TC-DBG] /tc msg by " + p.getName() + " len=" + msg.length());
                }

                // Service handles not-in-team errors
                plugin.teams().sendTeamChat(p, msg);
                return true;
            }

            // Toggle mode: /tc
            if (!plugin.getConfig().getBoolean("chat.toggle_enabled", true)) {
                plugin.msg().send(p, "teamchat_toggle_disabled");
                return true;
            }

            // Require being in a team to toggle
            if (plugin.teams().getTeamByPlayer(p.getUniqueId()).isEmpty()) {
                plugin.msg().send(p, "team_not_in_team");
                if (debug) {
                    plugin.getLogger().info("[TC-DBG] toggle blocked (not in team) for " + p.getName());
                }
                return true;
            }

            final boolean nowOn = plugin.teams().toggleTeamChat(p.getUniqueId());

            // ✅ Persist toggle in Redis (for cross-server swaps)
            final var bus = plugin.teamChatBus();
            if (bus instanceof RedisTeamChatBus r) {
                r.setTeamChatMode(p.getUniqueId(), nowOn);
            }

            if (debug) {
                plugin.getLogger().info("[TC-DBG] " + p.getName() + " toggled -> " + (nowOn ? "ON" : "OFF"));
            }

            plugin.msg().send(p, nowOn ? "teamchat_on" : "teamchat_off");
            return true;

        } catch (TeamServiceException ex) {
            CommandErrors.send(p, plugin, ex);

            if (debug) {
                plugin.getLogger().info("[TC-DBG] TeamServiceException for " + p.getName() + ": " +
                        (ex.code() == null ? "null" : ex.code().name()));
            }
            return true;

        } catch (Exception ex) {
            p.sendMessage(plugin.msg().prefix() + "An error occurred.");
            plugin.getLogger().severe("TeamChat command error: " +
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return true;
        }
    }
}
