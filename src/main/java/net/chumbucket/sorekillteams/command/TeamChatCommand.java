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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TeamChatCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;

    public TeamChatCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg().send(sender, "player_only");
            return true;
        }
        if (!p.hasPermission("sorekillteams.chat")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            plugin.msg().send(p, "teamchat_disabled");
            return true;
        }

        // One-off message
        if (args.length > 0) {
            if (plugin.teams().getTeamByPlayer(p.getUniqueId()).isEmpty()) {
                plugin.msg().send(p, "team_not_in_team");
                return true;
            }
            String msg = String.join(" ", args).trim();
            if (msg.isEmpty()) return true;
            plugin.teams().sendTeamChat(p, msg);
            return true;
        }

        // Toggle mode
        if (!plugin.getConfig().getBoolean("chat.toggle_enabled", true)) {
            plugin.msg().send(p, "teamchat_toggle_disabled");
            return true;
        }

        if (plugin.teams().getTeamByPlayer(p.getUniqueId()).isEmpty()) {
            plugin.msg().send(p, "team_not_in_team");
            return true;
        }

        boolean nowOn = plugin.teams().toggleTeamChat(p.getUniqueId());
        plugin.msg().send(p, nowOn ? "teamchat_on" : "teamchat_off");
        return true;
    }
}