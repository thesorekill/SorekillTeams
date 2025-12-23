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

import java.util.Locale;

public final class AdminCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;

    public AdminCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // Extra safety: command-level permission is also declared in plugin.yml
        if (!sender.hasPermission("sorekillteams.admin")) {
            plugin.msg().send(sender, "no_permission");
            return true;
        }

        if (args.length == 0) {
            plugin.msg().send(sender, "admin_usage");
            return true;
        }

        final boolean debug = plugin.getConfig().getBoolean("commands.debug", false);
        final String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload", "rl", "r" -> {
                if (!requirePerm(sender, "sorekillteams.reload")) return true;

                if (debug) plugin.getLogger().info("[ADMIN-DBG] reload by " + sender.getName());

                plugin.reloadEverything();
                plugin.msg().send(sender, "reloaded");
                return true;
            }

            case "version", "ver", "v" -> {
                if (!requirePerm(sender, "sorekillteams.version")) return true;

                if (debug) plugin.getLogger().info("[ADMIN-DBG] version by " + sender.getName());

                plugin.msg().send(sender, "version",
                        "{version}", plugin.getDescription().getVersion()
                );
                return true;
            }

            default -> {
                plugin.msg().send(sender, "unknown_command");
                plugin.msg().send(sender, "admin_usage");
                return true;
            }
        }
    }

    private boolean requirePerm(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return true;
        plugin.msg().send(sender, "no_permission");
        return false;
    }
}
