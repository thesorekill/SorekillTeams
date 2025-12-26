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
import org.bukkit.entity.Player;

public final class TeamCreateCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    public TeamCreateCommands(SorekillTeamsPlugin plugin, Object ignoredNameValidator) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        if (!sub.equalsIgnoreCase("create")) return false;

        if (!p.hasPermission("sorekillteams.create")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        // GUI behavior:
        // /team create opens the main menu (which contains "Create a Team" button / flow)
        final boolean menusEnabled = plugin.getConfig().getBoolean("menus.enabled", true);
        if (!menusEnabled || plugin.menuRouter() == null) {
            plugin.msg().send(p, "unknown_command");
            plugin.msg().send(p, "team_usage");
            return true;
        }

        plugin.menuRouter().open(p, "main");
        return true;
    }
}
