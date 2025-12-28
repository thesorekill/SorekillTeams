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
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.util.Menus;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class MainMenu {

    private MainMenu() {}

    public static void open(SorekillTeamsPlugin plugin, Player p) {
        if (plugin == null || p == null) return;
        if (plugin.menus() == null) return;
        if (!plugin.menus().enabledInConfigYml()) return;

        ConfigurationSection menu = plugin.menus().menu("main");
        if (menu == null) return;

        String title = Msg.color(plugin.menus().str(menu, "title", "&8ᴛᴇᴀᴍꜱ"));
        int rows = Menus.clampRows(plugin.menus().integer(menu, "rows", 3));
        int size = rows * 9;

        Inventory inv = Bukkit.createInventory(p, size, title);

        // filler
        ConfigurationSection filler = menu.getConfigurationSection("filler");
        boolean fillerEnabled = plugin.menus().bool(filler, "enabled", true);
        if (fillerEnabled) {
            Material fillMat = plugin.menus().material(filler, "material", Material.GRAY_STAINED_GLASS_PANE);
            String fillName = Msg.color(plugin.menus().str(filler, "name", " "));
            ItemStack fill = item(fillMat, fillName, List.of());
            for (int i = 0; i < size; i++) inv.setItem(i, fill);
        }

        Team team = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);

        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items != null) {
            // Dynamic team_or_create
            ConfigurationSection toc = items.getConfigurationSection("team_or_create");
            if (toc != null) {
                int slot = Menus.clampSlot(toc.getInt("slot", 11), size);

                ConfigurationSection chosen = (team == null)
                        ? toc.getConfigurationSection("when_not_in_team")
                        : toc.getConfigurationSection("when_in_team");

                if (chosen != null) {
                    ItemStack it = item(
                            plugin.menus().material(chosen, "material", Material.BOOK),
                            apply(plugin, p, team, plugin.menus().str(chosen, "name", "&bTeam")),
                            applyList(plugin, p, team, plugin.menus().strList(chosen, "lore"))
                    );
                    inv.setItem(slot, it);
                }
            }

            // invites
            placeStatic(inv, size, plugin, p, team, items, "invites");

            // browse_teams
            placeStatic(inv, size, plugin, p, team, items, "browse_teams");

            // close
            placeStatic(inv, size, plugin, p, team, items, "close");
        }

        p.openInventory(inv);
    }

    public static boolean isMenu(SorekillTeamsPlugin plugin, String title) {
        if (plugin == null || plugin.menus() == null) return false;
        ConfigurationSection menu = plugin.menus().menu("main");
        if (menu == null) return false;
        String expected = Msg.color(plugin.menus().str(menu, "title", "&8ᴛᴇᴀᴍꜱ"));
        return expected.equals(title);
    }

    private static void placeStatic(Inventory inv, int size, SorekillTeamsPlugin plugin, Player p, Team team,
                                    ConfigurationSection items, String key) {
        ConfigurationSection s = items.getConfigurationSection(key);
        if (s == null) return;

        int slot = Menus.clampSlot(s.getInt("slot", 0), size);
        ItemStack it = item(
                plugin.menus().material(s, "material", Material.STONE),
                apply(plugin, p, team, plugin.menus().str(s, "name", "&fItem")),
                applyList(plugin, p, team, plugin.menus().strList(s, "lore"))
        );
        inv.setItem(slot, it);
    }

    private static List<String> applyList(SorekillTeamsPlugin plugin, Player p, Team team, List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(apply(plugin, p, team, s));
        return out;
    }

    private static String apply(SorekillTeamsPlugin plugin, Player viewer, Team team, String s) {
        if (s == null) return "";
        String out = s;

        // team placeholders
        String teamName = (team == null ? "None" : Msg.color(team.getName()));
        String ownerName = "None";
        String members = (team == null ? "0" : String.valueOf(team.getMembers().size()));

        if (team != null && team.getOwner() != null) {
            // use your existing helper if we can
            if (plugin.teams() instanceof SimpleTeamService sts) {
                ownerName = sts.nameOf(team.getOwner());
            } else {
                ownerName = "owner";
            }
        }

        // invite_count
        int inviteCount = plugin.invites().listActive(viewer.getUniqueId(), System.currentTimeMillis()).size();

        // ff/chat (shown only where used)
        String ff = (team == null ? "&7N/A" : (team.isFriendlyFireEnabled() ? "&cENABLED" : "&aDISABLED"));
        String chat = (plugin.teams().isTeamChatEnabled(viewer.getUniqueId()) ? "&aON" : "&cOFF");

        out = out.replace("{team}", teamName);
        out = out.replace("{owner}", ownerName);
        out = out.replace("{members}", members);
        out = out.replace("{invite_count}", String.valueOf(inviteCount));
        out = out.replace("{ff}", Msg.color(ff));
        out = out.replace("{chat}", Msg.color(chat));

        return Msg.color(out);
    }

    private static ItemStack item(Material mat, String name, List<String> lore) {
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
