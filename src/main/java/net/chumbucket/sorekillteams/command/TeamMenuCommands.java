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

public final class TeamMenuCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    public TeamMenuCommands(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        if (!sub.equalsIgnoreCase("menu")) return false;

        final boolean menusEnabled = plugin.getConfig().getBoolean("menus.enabled", true);
        final boolean enableMenuSub = plugin.getConfig().getBoolean("menus.enable_team_menu_subcommand", true);

        if (!menusEnabled || !enableMenuSub || plugin.menus() == null || plugin.menuRouter() == null) {
            plugin.msg().send(p, "unknown_command");
            plugin.msg().send(p, "team_usage");
            return true;
        }

        plugin.menuRouter().open(p, "main");
        return true;
    }
}
