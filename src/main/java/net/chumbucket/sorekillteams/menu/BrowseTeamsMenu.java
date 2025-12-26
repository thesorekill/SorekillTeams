/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.menu;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public final class BrowseTeamsMenu {
    private BrowseTeamsMenu() {}
    public static void open(SorekillTeamsPlugin plugin, Player p) {
        Inventory inv = Bukkit.createInventory(p, 36, Msg.color("&8ʙʀᴏᴡꜱᴇ ᴛᴇᴀᴍꜱ"));
        p.openInventory(inv);
    }
}
