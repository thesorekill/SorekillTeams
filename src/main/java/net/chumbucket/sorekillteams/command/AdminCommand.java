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

public final class AdminCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;

    public AdminCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.msg().format("unknown_command"));
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("sorekillteams.reload")) {
                    plugin.msg().send(sender, "no_permission");
                    return true;
                }
                plugin.reloadEverything();
                plugin.msg().send(sender, "reloaded");
                return true;
            }
            case "version" -> {
                if (!sender.hasPermission("sorekillteams.version")) {
                    plugin.msg().send(sender, "no_permission");
                    return true;
                }
                plugin.msg().send(sender, "version", "{version}", plugin.getDescription().getVersion());
                return true;
            }
            default -> {
                sender.sendMessage(plugin.msg().format("unknown_command"));
                return true;
            }
        }
    }
}
