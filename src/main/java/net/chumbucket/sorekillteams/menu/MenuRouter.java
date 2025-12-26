package net.chumbucket.sorekillteams.menu;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamInvite;
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

import java.text.SimpleDateFormat;
import java.util.*;

public final class MenuRouter {

    private final SorekillTeamsPlugin plugin;

    public MenuRouter(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p, String menuKey) {
        if (plugin == null || p == null) return;
        if (plugin.menus() == null) return;
        if (!plugin.menus().enabledInConfigYml()) return;

        ConfigurationSection menu = plugin.menus().menu(menuKey);
        if (menu == null) return;

        String title = Msg.color(plugin.menus().str(menu, "title", "&8ᴛᴇᴀᴍꜱ"));
        int rows = Menus.clampRows(plugin.menus().integer(menu, "rows", 3));
        int size = rows * 9;

        // Use MenuHolder so listener can route clicks
        MenuHolder holder = new MenuHolder(menuKey, p);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

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

        // Static items
        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items != null) {
            // Special case: team_or_create on main
            ConfigurationSection toc = items.getConfigurationSection("team_or_create");
            if (toc != null) {
                int slot = Menus.clampSlot(toc.getInt("slot", 11), size);
                ConfigurationSection chosen = (team == null)
                        ? toc.getConfigurationSection("when_not_in_team")
                        : toc.getConfigurationSection("when_in_team");

                if (chosen != null) {
                    ItemStack it = item(
                            plugin.menus().material(chosen, "material", Material.BOOK),
                            apply(p, team, plugin.menus().str(chosen, "name", "&bTeam")),
                            applyList(p, team, plugin.menus().strList(chosen, "lore"))
                    );
                    inv.setItem(slot, it);

                    String action = plugin.menus().str(chosen, "action", "NONE");
                    boolean close = plugin.menus().bool(chosen, "close", false);
                    holder.bind(slot, action, close);
                }
            }

            // All other static items use standard shape
            bindStatic(inv, holder, size, p, team, items, "invites");
            bindStatic(inv, holder, size, p, team, items, "browse_teams");
            bindStatic(inv, holder, size, p, team, items, "back");
            bindStatic(inv, holder, size, p, team, items, "close");
            bindStatic(inv, holder, size, p, team, items, "header");
        }

        // Dynamic list (invites / teams)
        ConfigurationSection list = menu.getConfigurationSection("list");
        if (list != null) {
            String type = plugin.menus().str(list, "type", "").toUpperCase(Locale.ROOT);
            List<Integer> slots = list.getIntegerList("slots");
            if (slots == null) slots = List.of();

            ConfigurationSection itemSec = list.getConfigurationSection("item");
            Material mat = plugin.menus().material(itemSec, "material", Material.PAPER);
            String name = plugin.menus().str(itemSec, "name", "&fItem");
            List<String> lore = plugin.menus().strList(itemSec, "lore");

            boolean closeOnClick = plugin.menus().bool(list, "close_on_click", false);

            if (type.equals("INVITES")) {
                List<TeamInvite> invs = new ArrayList<>(plugin.teams().getInvites(p.getUniqueId()));
                invs.sort(Comparator.comparingLong(TeamInvite::getExpiresAtMs));

                for (int i = 0; i < Math.min(slots.size(), invs.size()); i++) {
                    int slot = Menus.clampSlot(slots.get(i), size);
                    TeamInvite invt = invs.get(i);

                    String inviteTeam = plugin.teams().getTeamById(invt.getTeamId())
                            .map(Team::getName)
                            .orElse(invt.getTeamName() == null ? "Team" : invt.getTeamName());

                    String inviteOwner = nameOf(invt.getInviter());
                    String expires = formatExpires(invt.getExpiresAtMs());

                    String builtName = Msg.color(name
                            .replace("{invite_team}", Msg.color(inviteTeam)));

                    List<String> builtLore = new ArrayList<>();
                    for (String line : lore) {
                        builtLore.add(Msg.color(
                                line.replace("{invite_team}", Msg.color(inviteTeam))
                                        .replace("{invite_owner}", inviteOwner)
                                        .replace("{invite_expires}", expires)
                        ));
                    }

                    inv.setItem(slot, item(mat, builtName, builtLore));

                    String left = plugin.menus().str(list, "left_click", "NONE")
                            .replace("{invite_team_id}", invt.getTeamId().toString())
                            .replace("{invite_team}", inviteTeam);

                    String right = plugin.menus().str(list, "right_click", "NONE")
                            .replace("{invite_team_id}", invt.getTeamId().toString())
                            .replace("{invite_team}", inviteTeam);

                    holder.bind(slot, left, right, closeOnClick);
                }
            }

            if (type.equals("TEAMS")) {
                List<Team> teams = new ArrayList<>();
                if (plugin.teams() instanceof SimpleTeamService sts) {
                    teams.addAll(sts.allTeams());
                }
                teams.sort(Comparator.comparing(t -> (t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT))));

                for (int i = 0; i < Math.min(slots.size(), teams.size()); i++) {
                    int slot = Menus.clampSlot(slots.get(i), size);
                    Team t = teams.get(i);

                    ItemStack it = item(
                            mat,
                            apply(p, team, name.replace("{team}", Msg.color(t.getName()))),
                            applyList(p, team, lore
                                    .stream()
                                    .map(x -> x.replace("{team}", Msg.color(t.getName())))
                                    .toList())
                    );

                    inv.setItem(slot, it);
                    holder.bind(slot, "NONE", closeOnClick);
                }
            }
        }

        p.openInventory(inv);
    }

    public void runAction(Player p, String action) {
        if (p == null || action == null) return;
        String a = action.trim();
        if (a.isEmpty() || a.equalsIgnoreCase("NONE")) return;

        if (a.equalsIgnoreCase("CLOSE")) {
            p.closeInventory();
            return;
        }

        if (a.regionMatches(true, 0, "OPEN:", 0, "OPEN:".length())) {
            String key = a.substring("OPEN:".length()).trim();
            if (!key.isEmpty()) open(p, key);
            return;
        }

        if (a.regionMatches(true, 0, "FLOW:", 0, "FLOW:".length())) {
            String flow = a.substring("FLOW:".length()).trim().toUpperCase(Locale.ROOT);
            if (flow.equals("CREATE_TEAM")) {
                CreateTeamFlow.begin(plugin, p);
            }
            return;
        }

        if (a.regionMatches(true, 0, "COMMAND:", 0, "COMMAND:".length())) {
            String cmd = a.substring("COMMAND:".length()).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isBlank()) {
                p.performCommand(cmd);
            }
            return;
        }
    }

    // --------------------
    // helpers
    // --------------------

    private void bindStatic(Inventory inv, MenuHolder holder, int size, Player viewer, Team team,
                            ConfigurationSection items, String key) {
        ConfigurationSection s = items.getConfigurationSection(key);
        if (s == null) return;

        int slot = Menus.clampSlot(s.getInt("slot", 0), size);

        ItemStack it = item(
                plugin.menus().material(s, "material", Material.STONE),
                apply(viewer, team, plugin.menus().str(s, "name", "&fItem")),
                applyList(viewer, team, plugin.menus().strList(s, "lore"))
        );

        inv.setItem(slot, it);

        String action = plugin.menus().str(s, "action", "NONE");
        boolean close = plugin.menus().bool(s, "close", false);
        holder.bind(slot, action, close);
    }

    private List<String> applyList(Player viewer, Team team, List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(apply(viewer, team, s));
        return out;
    }

    private String apply(Player viewer, Team team, String s) {
        if (s == null) return "";
        String out = s;

        String teamName = (team == null ? "None" : Msg.color(team.getName()));
        String ownerName = "None";
        String members = (team == null ? "0" : String.valueOf(team.getMembers().size()));

        if (team != null && team.getOwner() != null) {
            ownerName = nameOf(team.getOwner());
        }

        int inviteCount = plugin.teams().getInvites(viewer.getUniqueId()).size();
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

    private String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();

        var off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();

        return uuid.toString().substring(0, 8);
    }

    private static String formatExpires(long ms) {
        try {
            return new SimpleDateFormat("HH:mm:ss").format(new Date(ms));
        } catch (Exception e) {
            return String.valueOf(ms);
        }
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
