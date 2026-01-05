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
import org.bukkit.entity.Player;

import java.util.Locale;

public final class TeamChatCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    public TeamChatCommands(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        if (!(sub.equals("chat") || sub.equals("tc") || sub.equals("teamchat"))) return false;

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

        // /team chat   OR   /team tc
        if (args.length < 2) {
            boolean newState = plugin.teams().toggleTeamChat(p.getUniqueId());

            // ✅ Persist toggle in Redis
            final var bus = plugin.teamChatBus();
            if (bus instanceof RedisTeamChatBus r) {
                r.setTeamChatMode(p.getUniqueId(), newState);
            }

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

                // ✅ Persist toggle in Redis
                final var bus = plugin.teamChatBus();
                if (bus instanceof RedisTeamChatBus r) {
                    r.setTeamChatMode(p.getUniqueId(), true);
                }

                plugin.msg().send(p, "teamchat_on");
                if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " teamchat -> ON");
                return true;
            }
            case "off", "disable", "disabled", "false" -> {
                plugin.teams().setTeamChatEnabled(p.getUniqueId(), false);

                // ✅ Persist toggle in Redis
                final var bus = plugin.teamChatBus();
                if (bus instanceof RedisTeamChatBus r) {
                    r.setTeamChatMode(p.getUniqueId(), false);
                }

                plugin.msg().send(p, "teamchat_off");
                if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " teamchat -> OFF");
                return true;
            }
            case "toggle" -> {
                boolean newState = plugin.teams().toggleTeamChat(p.getUniqueId());

                // ✅ Persist toggle in Redis
                final var bus = plugin.teamChatBus();
                if (bus instanceof RedisTeamChatBus r) {
                    r.setTeamChatMode(p.getUniqueId(), newState);
                }

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
}
