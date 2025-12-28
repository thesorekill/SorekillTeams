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
import net.chumbucket.sorekillteams.model.TeamInvite;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ConfirmMenu {

    private ConfirmMenu() {}

    /**
     * Reusable confirm UI (1 row):
     * [0 filler] [1 DENY red] [2 filler] [3 filler] [4 owner head] [5 filler] [6 filler] [7 ACCEPT green] [8 filler]
     */
    public static void open(SorekillTeamsPlugin plugin,
                            Player viewer,
                            String title,
                            UUID headOwner,
                            String denyName,
                            List<String> denyLore,
                            String denyAction,
                            String acceptName,
                            List<String> acceptLore,
                            String acceptAction) {

        if (plugin == null || viewer == null) return;

        MenuHolder holder = new MenuHolder("confirm", viewer);
        Inventory inv = Bukkit.createInventory(holder, 9, Msg.color(title == null ? "&8Confirm" : title));
        holder.setInventory(inv);

        // Filler
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 9; i++) inv.setItem(i, filler);

        // Deny (slot 1)
        inv.setItem(1, item(Material.RED_STAINED_GLASS_PANE,
                Msg.color(denyName == null ? "&cCANCEL" : denyName),
                colorLore(denyLore)));
        holder.bind(1, denyAction, true);

        // Head (slot 4)
        inv.setItem(4, head(headOwner, "&b", List.of()));
        holder.bind(4, "NONE", false);

        // Accept (slot 7)
        inv.setItem(7, item(Material.LIME_STAINED_GLASS_PANE,
                Msg.color(acceptName == null ? "&aCONFIRM" : acceptName),
                colorLore(acceptLore)));
        holder.bind(7, acceptAction, true);

        viewer.openInventory(inv);
    }

    /** Invite confirm wrapper (used by MenuRouter). */
    public static void openInviteConfirm(SorekillTeamsPlugin plugin, Player viewer, UUID teamId) {
        if (plugin == null || viewer == null || teamId == null) return;

        TeamInvite inv = plugin.invites().get(viewer.getUniqueId(), teamId).orElse(null);
        if (inv == null) {
            plugin.msg().send(viewer, "team_no_invites");
            return;
        }

        UUID inviter = inv.getInviter();
        String inviterName = (inviter == null) ? "unknown" : nameOf(inviter);

        String teamName = plugin.teams().getTeamById(teamId)
                .map(t -> t.getName() == null ? "Team" : t.getName())
                .orElse(inv.getTeamName() == null ? "Team" : inv.getTeamName());

        String title = "&8Invite";
        List<String> denyLore = List.of("&7Click to deny the invite", "&7from &f" + inviterName);
        List<String> acceptLore = List.of("&7Click to accept the invite", "&7to join &b" + Msg.color(teamName));

        String denyAction = "COMMAND:team deny " + teamId;
        String acceptAction = "COMMAND:team accept " + teamId;

        open(plugin, viewer, title, inviter,
                "&cCANCEL", denyLore, denyAction,
                "&aCONFIRM", acceptLore, acceptAction);
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

    private static ItemStack head(UUID owner, String namePrefix, List<String> lore) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = skull.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            if (owner != null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
                meta.setOwningPlayer(op);
            }
            meta.setDisplayName(Msg.color((namePrefix == null ? "" : namePrefix) + " "));
            if (lore != null && !lore.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (String line : lore) out.add(Msg.color(line));
                meta.setLore(out);
            }
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private static List<String> colorLore(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(Msg.color(Objects.toString(s, "")));
        return out;
    }

    private static String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();
        return uuid.toString().substring(0, 8);
    }
}
