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

import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Tiny shared ItemStack builder (same behavior as your original item()). */
public final class MenuItems {

    private MenuItems() {}

    public static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat == null ? Material.STONE : mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color(name == null ? "" : name));
            if (lore != null && !lore.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (String line : lore) out.add(Msg.color(line));
                meta.setLore(out);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }
}
