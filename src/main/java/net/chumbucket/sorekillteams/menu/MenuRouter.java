package net.chumbucket.sorekillteams.menu;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamHome;
import net.chumbucket.sorekillteams.model.TeamInvite;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.util.Menus;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MenuRouter {

    private final SorekillTeamsPlugin plugin;

    // viewer -> task id for cycling member head
    private final Map<UUID, Integer> cyclingTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> cyclingIndex = new ConcurrentHashMap<>();

    private static final long MEMBER_HEAD_CYCLE_TICKS = 40L; // 2s

    public MenuRouter(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    // --------------------
    // Public open
    // --------------------

    public void open(Player p, String menuKey) {
        open(p, menuKey, 0);
    }

    // --------------------
    // Core open with paging
    // --------------------

    private void open(Player p, String menuKey, int page) {
        if (plugin == null || p == null) return;
        if (plugin.menus() == null) return;
        if (!plugin.menus().enabledInConfigYml()) return;

        stopCycling(p.getUniqueId());

        ConfigurationSection menu = plugin.menus().menu(menuKey);
        if (menu == null) return;

        // IMPORTANT: compute viewerTeam BEFORE applying title placeholders
        Team viewerTeam = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);

        String rawTitle = plugin.menus().str(menu, "title", "&8ᴛᴇᴀᴍꜱ");
        String title = apply(p, viewerTeam, rawTitle); // apply() already colors

        int rows = Menus.clampRows(plugin.menus().integer(menu, "rows", 3));
        int size = rows * 9;

        MenuHolder holder = new MenuHolder(menuKey, p);
        holder.setPage(Math.max(0, page));

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

        // static items
        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items != null) {
            // main team_or_create
            ConfigurationSection toc = items.getConfigurationSection("team_or_create");
            if (toc != null) {
                int slot = Menus.clampSlot(toc.getInt("slot", 11), size);

                ConfigurationSection chosen = (viewerTeam == null)
                        ? toc.getConfigurationSection("when_not_in_team")
                        : toc.getConfigurationSection("when_in_team");

                if (chosen != null) {
                    ItemStack it = item(
                            plugin.menus().material(chosen, "material", Material.BOOK),
                            apply(p, viewerTeam, plugin.menus().str(chosen, "name", "&bTeam")),
                            applyList(p, viewerTeam, plugin.menus().strList(chosen, "lore"))
                    );
                    inv.setItem(slot, it);

                    String action = plugin.menus().str(chosen, "action", "NONE");
                    boolean close = plugin.menus().bool(chosen, "close", false);
                    holder.bind(slot, action, close);
                }
            }

            // team_info special dynamics
            if ("team_info".equalsIgnoreCase(menuKey) && viewerTeam != null) {
                // nametag: owner -> disband confirm, member -> leave confirm
                bindStatic(inv, holder, size, p, viewerTeam, items, "name_tag", actionForNameTag(p, viewerTeam));

                // sword: teamchat toggle
                bindStatic(inv, holder, size, p, viewerTeam, items, "team_chat", "TEAMCHAT:TOGGLE");

                // shield: friendly fire toggle (owner only) - your runAction enforces owner
                bindStatic(inv, holder, size, p, viewerTeam, items, "friendly_fire", "FF:TOGGLE");

                // members head: open members menu + cycles
                ConfigurationSection mh = items.getConfigurationSection("members_head");
                if (mh != null) {
                    int slot = Menus.clampSlot(mh.getInt("slot", 13), size);

                    ItemStack init = buildMemberCycleHead(p, viewerTeam, mh, 0);
                    inv.setItem(slot, init);

                    String action = plugin.menus().str(mh, "action", "OPEN:team_members");
                    boolean close = plugin.menus().bool(mh, "close", false);
                    holder.bind(slot, action, close);

                    startMemberHeadCycling(p, holder, slot, mh);
                }

                // home bed dynamic
                renderTeamHomeBed(inv, holder, size, p, viewerTeam, items);
            } else {
                // generic statics
                bindStatic(inv, holder, size, p, viewerTeam, items, "invites", null);
                bindStatic(inv, holder, size, p, viewerTeam, items, "browse_teams", null);
                bindStatic(inv, holder, size, p, viewerTeam, items, "close", null);
                bindStatic(inv, holder, size, p, viewerTeam, items, "header", null);
            }
        }

        // dynamic list block
        ConfigurationSection list = menu.getConfigurationSection("list");
        if (list != null) {
            renderDynamicList(menuKey, inv, holder, size, p, viewerTeam, list);
            renderPagination(inv, holder, size, p, viewerTeam, menu);
        }

        p.openInventory(inv);
    }

    // --------------------
    // Actions
    // --------------------

    public void runAction(Player p, String action) {
        if (p == null || action == null) return;
        String a = action.trim();
        if (a.isEmpty() || a.equalsIgnoreCase("NONE")) return;

        // OPEN:<menuKey>
        if (a.regionMatches(true, 0, "OPEN:", 0, "OPEN:".length())) {
            String key = a.substring("OPEN:".length()).trim();
            if (!key.isEmpty()) open(p, key, 0);
            return;
        }

        // PAGE:NEXT / PAGE:PREV
        if (a.regionMatches(true, 0, "PAGE:", 0, "PAGE:".length())) {
            String dir = a.substring("PAGE:".length()).trim().toUpperCase(Locale.ROOT);

            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder)) return;

            int newPage = holder.page();
            if (dir.equals("NEXT")) newPage++;
            if (dir.equals("PREV")) newPage = Math.max(0, newPage - 1);

            open(p, holder.menuKey(), newPage);
            return;
        }

        // FLOW:CREATE_TEAM
        if (a.regionMatches(true, 0, "FLOW:", 0, "FLOW:".length())) {
            String flow = a.substring("FLOW:".length()).trim().toUpperCase(Locale.ROOT);
            if (flow.equals("CREATE_TEAM")) {
                CreateTeamFlow.begin(plugin, p);
            }
            return;
        }

        // COMMAND:<cmd>
        if (a.regionMatches(true, 0, "COMMAND:", 0, "COMMAND:".length())) {
            String cmd = a.substring("COMMAND:".length()).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isBlank()) p.performCommand(cmd);
            return;
        }

        // CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:<teamId>
        if (a.regionMatches(true, 0, "CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:", 0,
                "CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:".length())) {
            String id = a.substring("CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:".length()).trim();
            UUID teamId = safeUuid(id);
            denyInviteAndReturn(p, teamId);
            return;
        }

        // CONFIRM:<variant>:<payload...>
        if (a.regionMatches(true, 0, "CONFIRM:", 0, "CONFIRM:".length())) {
            String rest = a.substring("CONFIRM:".length()).trim();
            openConfirmFromAction(p, rest);
            return;
        }

        // TEAMCHAT:TOGGLE
        if (a.equalsIgnoreCase("TEAMCHAT:TOGGLE")) {

            // Respect config "toggle disabled" (matches your messages.yml keys)
            if (!plugin.getConfig().getBoolean("team_chat.toggle_enabled", true)) {
                plugin.msg().send(p, "teamchat_toggle_disabled");
                return;
            }

            plugin.teams().toggleTeamChat(p.getUniqueId());

            boolean enabled = plugin.teams().isTeamChatEnabled(p.getUniqueId());
            plugin.msg().send(p, enabled ? "teamchat_on" : "teamchat_off");

            open(p, "team_info", 0);
            return;
        }

        // FF:TOGGLE (owner only)
        if (a.equalsIgnoreCase("FF:TOGGLE")) {
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
            if (t == null) return;

            if (!p.getUniqueId().equals(t.getOwner())) return;

            p.performCommand("team ff toggle");
            plugin.getServer().getScheduler().runTask(plugin, () -> open(p, "team_info", 0));
            return;
        }

        // TEAMHOME:SET
        if (a.equalsIgnoreCase("TEAMHOME:SET")) {
            int max = Math.max(1, plugin.getConfig().getInt("homes.max_homes", 1));
            if (max <= 1) {
                p.performCommand("team sethome team");
                plugin.getServer().getScheduler().runTask(plugin, () -> open(p, "team_info", 0));
            } else {
                p.performCommand("team sethome");
            }
            return;
        }

        // TEAMHOME:TELEPORT_ONE
        if (a.equalsIgnoreCase("TEAMHOME:TELEPORT_ONE")) {
            int max = Math.max(1, plugin.getConfig().getInt("homes.max_homes", 1));
            if (max <= 1) {
                p.performCommand("team home team");
            } else {
                p.performCommand("team home");
            }
            return;
        }

        // TEAMHOME:TELEPORT:<name>
        if (a.regionMatches(true, 0, "TEAMHOME:TELEPORT:", 0, "TEAMHOME:TELEPORT:".length())) {
            String homeName = a.substring("TEAMHOME:TELEPORT:".length()).trim();
            if (!homeName.isBlank()) {
                p.performCommand("team home " + homeName);
            }
            return;
        }

        // CLOSE
        if (a.equalsIgnoreCase("CLOSE")) {
            p.closeInventory();
        }
    }

    // --------------------
    // Confirm menu routing
    // --------------------

    private void openConfirmFromAction(Player p, String rest) {
        if (p == null || rest == null) return;

        String[] parts = rest.split(":", 2);
        String variant = parts[0].trim().toLowerCase(Locale.ROOT);
        String payload = (parts.length > 1 ? parts[1].trim() : "");

        openConfirmMenu(p, variant, payload);
    }

    private void openConfirmMenu(Player p, String variant, String payload) {
        if (plugin.menus() == null) return;

        ConfigurationSection root = plugin.menus().menu("confirm");
        if (root == null) return;

        ConfigurationSection variants = root.getConfigurationSection("variants");
        ConfigurationSection v = (variants == null ? null : variants.getConfigurationSection(variant));
        if (v == null) return;

        int rows = Menus.clampRows(plugin.menus().integer(root, "rows", 3));
        int size = rows * 9;

        // title can also contain placeholders in variants later; keep as-is for now
        String title = Msg.color(plugin.menus().str(v, "title", "&8ᴄᴏɴꜰɪʀᴍ"));

        MenuHolder holder = new MenuHolder("confirm:" + variant, p);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        // filler
        ConfigurationSection filler = root.getConfigurationSection("filler");
        boolean fillerEnabled = plugin.menus().bool(filler, "enabled", true);
        if (fillerEnabled) {
            Material fillMat = plugin.menus().material(filler, "material", Material.GRAY_STAINED_GLASS_PANE);
            String fillName = Msg.color(plugin.menus().str(filler, "name", " "));
            ItemStack fill = item(fillMat, fillName, List.of());
            for (int i = 0; i < size; i++) inv.setItem(i, fill);
        }

        // layout slots (null-safe)
        ConfigurationSection layout = root.getConfigurationSection("layout");
        int denySlot = Menus.clampSlot(layout == null ? 10 : layout.getInt("deny_slot", 10), size);
        int subjSlot = Menus.clampSlot(layout == null ? 13 : layout.getInt("subject_slot", 13), size);
        int accSlot  = Menus.clampSlot(layout == null ? 16 : layout.getInt("accept_slot", 16), size);

        // buttons (null-safe)
        ConfigurationSection buttons = root.getConfigurationSection("buttons");
        ConfigurationSection denyB = buttons == null ? null : buttons.getConfigurationSection("deny");
        ConfigurationSection accB  = buttons == null ? null : buttons.getConfigurationSection("accept");
        ConfigurationSection subB  = buttons == null ? null : buttons.getConfigurationSection("subject");

        // resolve subject + actions
        String subjectName = "Confirm";
        String subtitle = plugin.menus().str(v, "subtitle", "");
        String acceptAction = plugin.menus().str(v, "default_accept_action", "NONE");
        String denyAction   = plugin.menus().str(v, "default_deny_action", "NONE");
        UUID subjectHeadUuid = null;

        if (variant.equals("invite")) {
            UUID teamId = safeUuid(payload);
            if (teamId != null) {
                Team t = plugin.teams().getTeamById(teamId).orElse(null);
                if (t != null) {
                    subjectName = (t.getName() == null ? "Team" : t.getName());
                    subjectHeadUuid = t.getOwner();

                    TeamInvite invt = findInvite(p.getUniqueId(), teamId);
                    String inviter = invt == null ? "unknown" : nameOf(invt.getInviter());

                    subtitle = subtitle
                            .replace("{inviter}", inviter)
                            .replace("{team}", Msg.color(subjectName));

                    acceptAction = "COMMAND:team accept " + teamId;
                    denyAction = "CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:" + teamId;
                }
            }
        }

        if (variant.equals("disband")) {
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
            if (t != null) {
                subjectName = (t.getName() == null ? "Team" : t.getName());
                subjectHeadUuid = t.getOwner();
                subtitle = subtitle.replace("{team}", Msg.color(subjectName));
                acceptAction = "COMMAND:team disband";
                denyAction = "OPEN:team_info";
            }
        }

        if (variant.equals("leave")) {
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
            if (t != null) {
                subjectName = (t.getName() == null ? "Team" : t.getName());
                subjectHeadUuid = t.getOwner();
                subtitle = subtitle.replace("{team}", Msg.color(subjectName));
                acceptAction = "COMMAND:team leave";
                denyAction = "OPEN:team_info";
            }
        }

        if (variant.equals("kick_member")) {
            UUID member = safeUuid(payload);
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);

            if (t != null && member != null) {
                subjectName = nameOf(member);
                subjectHeadUuid = member;

                subtitle = subtitle.replace("{member}", subjectName);
                acceptAction = "COMMAND:team kick " + subjectName;
                denyAction = "OPEN:team_members";
            }
        }

        // deny button
        if (denyB != null) {
            ItemStack it = item(
                    plugin.menus().material(denyB, "material", Material.RED_STAINED_GLASS_PANE),
                    Msg.color(plugin.menus().str(denyB, "name", "&cCANCEL")),
                    plugin.menus().strList(denyB, "lore")
            );
            inv.setItem(denySlot, it);
            boolean close = plugin.menus().bool(denyB, "close", true);
            holder.bind(denySlot, denyAction, close);
        }

        // accept button
        if (accB != null) {
            ItemStack it = item(
                    plugin.menus().material(accB, "material", Material.LIME_STAINED_GLASS_PANE),
                    Msg.color(plugin.menus().str(accB, "name", "&aCONFIRM")),
                    plugin.menus().strList(accB, "lore")
            );
            inv.setItem(accSlot, it);
            boolean close = plugin.menus().bool(accB, "close", true);
            holder.bind(accSlot, acceptAction, close);
        }

        // subject (team head is owner head)
        String subjectLine = (subB == null)
                ? "&b{subject}"
                : plugin.menus().str(subB, "name", "&b{subject}");

        subjectLine = subjectLine.replace("{subject}", Msg.color(subjectName));

        ItemStack subj = buildPlayerHead(
                subjectHeadUuid,
                Msg.color(subjectLine),
                List.of(Msg.color(subtitle == null ? "" : subtitle))
        );

        inv.setItem(subjSlot, subj);
        holder.bind(subjSlot, "NONE", false);

        p.openInventory(inv);
    }

    private TeamInvite findInvite(UUID invitee, UUID teamId) {
        if (invitee == null || teamId == null) return null;
        for (TeamInvite inv : plugin.teams().getInvites(invitee)) {
            if (inv == null) continue;
            if (teamId.equals(inv.getTeamId())) return inv;
        }
        return null;
    }

    private void denyInviteAndReturn(Player p, UUID teamId) {
        if (p == null || teamId == null) return;
        p.performCommand("team deny " + teamId);
        plugin.getServer().getScheduler().runTask(plugin, () -> open(p, "invites", 0));
    }

    // --------------------
    // Dynamic lists + pagination
    // --------------------

    private void renderDynamicList(String menuKey, Inventory inv, MenuHolder holder, int size, Player viewer, Team viewerTeam,
                                   ConfigurationSection list) {

        String type = plugin.menus().str(list, "type", "").toUpperCase(Locale.ROOT);
        List<Integer> slots = list.getIntegerList("slots");
        if (slots == null) slots = List.of();

        // ✅ perPage should come from pagination.per_page (if present), otherwise use slots size
        int perPage = Math.max(1, slots.size());
        ConfigurationSection menu = plugin.menus().menu(menuKey);
        if (menu != null) {
            ConfigurationSection pag = menu.getConfigurationSection("pagination");
            if (pag != null && plugin.menus().bool(pag, "enabled", false)) {
                perPage = Math.max(1, plugin.menus().integer(pag, "per_page", perPage));
            }
        }

        int page = Math.max(0, holder.page());
        int start = page * perPage;

        ConfigurationSection itemSec = list.getConfigurationSection("item");
        Material mat = plugin.menus().material(itemSec, "material", Material.PAPER);
        String name = plugin.menus().str(itemSec, "name", "&fItem");
        List<String> lore = plugin.menus().strList(itemSec, "lore");

        boolean closeOnClick = plugin.menus().bool(list, "close_on_click", false);
        String clickActionTemplate = plugin.menus().str(list, "click", "NONE");

        if (type.equals("INVITES")) {
            List<TeamInvite> invs = new ArrayList<>(plugin.teams().getInvites(viewer.getUniqueId()));
            invs.sort(Comparator.comparingLong(TeamInvite::getExpiresAtMs));

            List<TeamInvite> pageItems = slice(invs, start, perPage);

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                TeamInvite invt = pageItems.get(i);

                String inviteTeam = plugin.teams().getTeamById(invt.getTeamId())
                        .map(Team::getName)
                        .orElse(invt.getTeamName() == null ? "Team" : invt.getTeamName());

                String inviteOwner = nameOf(invt.getInviter());
                String expires = formatExpires(invt.getExpiresAtMs());

                String builtName = Msg.color(name.replace("{invite_team}", Msg.color(inviteTeam)));

                List<String> builtLore = new ArrayList<>();
                for (String line : lore) {
                    builtLore.add(Msg.color(
                            line.replace("{invite_team}", Msg.color(inviteTeam))
                                    .replace("{invite_owner}", inviteOwner)
                                    .replace("{invite_expires}", expires)
                    ));
                }

                inv.setItem(slot, item(mat, builtName, builtLore));

                String action = clickActionTemplate
                        .replace("{invite_team_id}", invt.getTeamId().toString())
                        .replace("{invite_team}", inviteTeam);

                holder.bind(slot, action, closeOnClick);
            }
            return;
        }

        if (type.equals("TEAMS")) {
            List<Team> teams = new ArrayList<>();
            if (plugin.teams() instanceof SimpleTeamService sts) {
                teams.addAll(sts.allTeams());
            }
            teams.sort(Comparator.comparing(t -> (t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT))));

            List<Team> pageItems = slice(teams, start, perPage);

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                Team t = pageItems.get(i);

                String owner = nameOf(t.getOwner());
                String members = String.valueOf(uniqueMemberCount(t));

                ItemStack it;
                if (mat == Material.PLAYER_HEAD) {
                    it = buildPlayerHead(t.getOwner(),
                            Msg.color(name.replace("{team}", Msg.color(t.getName()))),
                            lore.stream()
                                    .map(x -> Msg.color(x.replace("{team}", Msg.color(t.getName()))
                                            .replace("{owner}", owner)
                                            .replace("{members}", members)))
                                    .toList());
                } else {
                    it = item(
                            mat,
                            Msg.color(name.replace("{team}", Msg.color(t.getName()))),
                            lore.stream()
                                    .map(x -> Msg.color(x.replace("{team}", Msg.color(t.getName()))
                                            .replace("{owner}", owner)
                                            .replace("{members}", members)))
                                    .toList()
                    );
                }

                inv.setItem(slot, it);

                String action = clickActionTemplate
                        .replace("{team_id}", t.getId().toString())
                        .replace("{team}", t.getName() == null ? "Team" : t.getName());

                holder.bind(slot, action, closeOnClick);
            }
            return;
        }

        if (type.equals("TEAM_MEMBERS")) {
            if (viewerTeam == null) return;

            List<UUID> members = uniqueTeamMembers(viewerTeam);
            List<UUID> pageItems = slice(members, start, perPage);

            boolean isOwner = viewerTeam.getOwner() != null && viewerTeam.getOwner().equals(viewer.getUniqueId());

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                UUID u = pageItems.get(i);

                String memberName = nameOf(u);
                String role = (viewerTeam.getOwner() != null && viewerTeam.getOwner().equals(u)) ? "Owner" : "Member";

                String action = "NONE";
                if (isOwner && viewerTeam.getOwner() != null && !viewerTeam.getOwner().equals(u) && !viewer.getUniqueId().equals(u)) {
                    action = clickActionTemplate.replace("{member_uuid}", u.toString()).replace("{member_name}", memberName);
                }

                ItemStack head = buildPlayerHead(u,
                        Msg.color(name.replace("{member_name}", memberName).replace("{member_role}", role)),
                        lore.stream()
                                .map(x -> Msg.color(x.replace("{member_name}", memberName).replace("{member_role}", role)))
                                .toList());

                inv.setItem(slot, head);
                holder.bind(slot, action, closeOnClick);
            }
            return;
        }

        if (type.equals("TEAM_HOMES")) {
            if (viewerTeam == null) return;

            List<String> homes = getTeamHomeNames(viewerTeam);
            List<String> pageItems = slice(homes, start, perPage);

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                String homeName = pageItems.get(i);

                ItemStack it = item(
                        mat,
                        Msg.color(name.replace("{home_name}", homeName)),
                        lore.stream().map(x -> Msg.color(x.replace("{home_name}", homeName))).toList()
                );
                inv.setItem(slot, it);

                String action = clickActionTemplate.replace("{home_name}", homeName);
                holder.bind(slot, action, closeOnClick);
            }
        }
    }

    private void renderPagination(Inventory inv, MenuHolder holder, int size, Player viewer, Team viewerTeam,
                                  ConfigurationSection menu) {
        ConfigurationSection pag = menu.getConfigurationSection("pagination");
        if (pag == null) return;
        if (!plugin.menus().bool(pag, "enabled", false)) return;

        // compute pages based on list size
        ConfigurationSection list = menu.getConfigurationSection("list");
        if (list == null) return;

        String type = plugin.menus().str(list, "type", "").toUpperCase(Locale.ROOT);
        List<Integer> slots = list.getIntegerList("slots");
        if (slots == null) slots = List.of();

        int perPage = plugin.menus().integer(pag, "per_page", Math.max(1, slots.size()));
        perPage = Math.max(1, perPage);

        int total = 0;

        if (type.equals("INVITES")) total = plugin.teams().getInvites(viewer.getUniqueId()).size();
        if (type.equals("TEAMS") && plugin.teams() instanceof SimpleTeamService sts) total = sts.allTeams().size();
        if (type.equals("TEAM_MEMBERS") && viewerTeam != null) total = uniqueTeamMembers(viewerTeam).size();
        if (type.equals("TEAM_HOMES") && viewerTeam != null) total = getTeamHomeNames(viewerTeam).size();

        int pages = Math.max(1, (total + perPage - 1) / perPage);

        boolean hideIfSingle = plugin.menus().bool(pag, "hide_if_single_page", true);
        if (hideIfSingle && pages <= 1) return;

        int page = holder.page();
        if (page >= pages) page = pages - 1;

        final int pageDisplay = page + 1;
        final int pagesFinal = pages;

        // prev
        ConfigurationSection prev = pag.getConfigurationSection("prev");
        if (prev != null && page > 0) {
            int slot = Menus.clampSlot(prev.getInt("slot", 18), size);

            String prevName = plugin.menus().str(prev, "name", "&c← Prev")
                    .replace("{page}", String.valueOf(pageDisplay))
                    .replace("{pages}", String.valueOf(pagesFinal));

            List<String> prevLore = plugin.menus().strList(prev, "lore").stream()
                    .map(x -> Msg.color(x.replace("{page}", String.valueOf(pageDisplay))
                            .replace("{pages}", String.valueOf(pagesFinal))))
                    .toList();

            ItemStack it = item(
                    plugin.menus().material(prev, "material", Material.ARROW),
                    Msg.color(prevName),
                    prevLore
            );

            inv.setItem(slot, it);
            holder.bind(slot, "PAGE:PREV", false);
        }

        // next
        ConfigurationSection next = pag.getConfigurationSection("next");
        if (next != null && page < pages - 1) {
            int slot = Menus.clampSlot(next.getInt("slot", 26), size);

            String nextName = plugin.menus().str(next, "name", "&aNext →")
                    .replace("{page}", String.valueOf(pageDisplay))
                    .replace("{pages}", String.valueOf(pagesFinal));

            List<String> nextLore = plugin.menus().strList(next, "lore").stream()
                    .map(x -> Msg.color(x.replace("{page}", String.valueOf(pageDisplay))
                            .replace("{pages}", String.valueOf(pagesFinal))))
                    .toList();

            ItemStack it = item(
                    plugin.menus().material(next, "material", Material.ARROW),
                    Msg.color(nextName),
                    nextLore
            );

            inv.setItem(slot, it);
            holder.bind(slot, "PAGE:NEXT", false);
        }
    }

    // --------------------
    // Team Info: name tag action
    // --------------------

    private String actionForNameTag(Player viewer, Team team) {
        if (viewer == null || team == null) return "NONE";
        boolean isOwner = team.getOwner() != null && team.getOwner().equals(viewer.getUniqueId());
        return isOwner ? "CONFIRM:disband" : "CONFIRM:leave";
    }

    // --------------------
    // Team Info: home bed rendering
    // --------------------

    private void renderTeamHomeBed(Inventory inv, MenuHolder holder, int size, Player viewer, Team team, ConfigurationSection items) {
        if (inv == null || holder == null || viewer == null || team == null || items == null) return;

        ConfigurationSection sec = items.getConfigurationSection("team_home");
        if (sec == null) return;

        int slot = Menus.clampSlot(sec.getInt("slot", 14), size);

        boolean isOwner = team.getOwner() != null && team.getOwner().equals(viewer.getUniqueId());
        List<String> homes = getTeamHomeNames(team);

        int maxHomes = Math.max(1, plugin.getConfig().getInt("homes.max_homes", 1));

        if (homes.isEmpty()) {
            ConfigurationSection whenNone = sec.getConfigurationSection("when_none");
            if (whenNone == null) return;

            Material mat = plugin.menus().material(whenNone, "material", Material.GRAY_BED);
            String name = apply(viewer, team, plugin.menus().str(whenNone, "name", "&7Team Home"));
            List<String> lore = applyList(viewer, team, plugin.menus().strList(whenNone, "lore"));

            inv.setItem(slot, item(mat, name, lore));

            String action = "NONE";
            if (isOwner) {
                if (maxHomes <= 1) action = "TEAMHOME:SET";
                else action = plugin.menus().str(whenNone, "action_owner", "TEAMHOME:SET");
            } else {
                action = plugin.menus().str(whenNone, "action_member", "NONE");
            }

            holder.bind(slot, action, plugin.menus().bool(whenNone, "close", false));
            return;
        }

        if (homes.size() == 1) {
            ConfigurationSection whenOne = sec.getConfigurationSection("when_one");
            if (whenOne == null) return;

            Material mat = plugin.menus().material(whenOne, "material", Material.RED_BED);
            String name = apply(viewer, team, plugin.menus().str(whenOne, "name", "&cTeam Home"));
            List<String> lore = applyList(viewer, team, plugin.menus().strList(whenOne, "lore"));

            inv.setItem(slot, item(mat, name, lore));

            String action;
            if (maxHomes <= 1) {
                action = plugin.menus().str(whenOne, "action", "TEAMHOME:TELEPORT_ONE");
            } else {
                action = "TEAMHOME:TELEPORT:" + homes.get(0);
            }

            boolean close = plugin.menus().bool(whenOne, "close", true);
            holder.bind(slot, action, close);
            return;
        }

        // many
        ConfigurationSection whenMany = sec.getConfigurationSection("when_many");
        if (whenMany == null) return;

        Material mat = plugin.menus().material(whenMany, "material", Material.RED_BED);
        String name = apply(viewer, team, plugin.menus().str(whenMany, "name", "&cTeam Homes"));
        List<String> lore = applyList(viewer, team, plugin.menus().strList(whenMany, "lore"));

        inv.setItem(slot, item(mat, name, lore));

        String action = plugin.menus().str(whenMany, "action", "OPEN:team_homes");
        boolean close = plugin.menus().bool(whenMany, "close", false);
        holder.bind(slot, action, close);
    }

    // --------------------
    // Cycling member head
    // --------------------

    private void startMemberHeadCycling(Player viewer, MenuHolder holder, int slot, ConfigurationSection membersHeadSec) {
        UUID viewerId = viewer.getUniqueId();

        stopCycling(viewerId);

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline()) {
                stopCycling(viewerId);
                return;
            }

            if (viewer.getOpenInventory() == null
                    || viewer.getOpenInventory().getTopInventory() == null
                    || viewer.getOpenInventory().getTopInventory().getHolder() != holder) {
                stopCycling(viewerId);
                return;
            }

            if (!"team_info".equalsIgnoreCase(holder.menuKey())) {
                stopCycling(viewerId);
                return;
            }

            Team team = plugin.teams().getTeamByPlayer(viewerId).orElse(null);
            if (team == null) {
                stopCycling(viewerId);
                return;
            }

            List<UUID> members = uniqueTeamMembers(team);
            if (members.isEmpty()) return;

            int idx = cyclingIndex.getOrDefault(viewerId, 0);
            int nextIdx = (idx + 1) % members.size();
            cyclingIndex.put(viewerId, nextIdx);

            Inventory top = viewer.getOpenInventory().getTopInventory();
            ItemStack head = buildMemberCycleHead(viewer, team, membersHeadSec, nextIdx);
            top.setItem(slot, head);

        }, 1L, MEMBER_HEAD_CYCLE_TICKS).getTaskId();

        cyclingTasks.put(viewerId, taskId);
        cyclingIndex.putIfAbsent(viewerId, 0);
    }

    public void stopCycling(UUID viewerId) {
        if (viewerId == null) return;

        Integer taskId = cyclingTasks.remove(viewerId);
        if (taskId != null) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Exception ignored) {}
        }
        cyclingIndex.remove(viewerId);
    }

    private ItemStack buildMemberCycleHead(Player viewer, Team team, ConfigurationSection mh, int idx) {
        List<UUID> members = uniqueTeamMembers(team);
        if (members.isEmpty()) return new ItemStack(Material.PLAYER_HEAD);

        UUID who = members.get(Math.floorMod(idx, members.size()));

        String name = apply(viewer, team, plugin.menus().str(mh, "name", "&bMembers"));
        List<String> lore = applyList(viewer, team, plugin.menus().strList(mh, "lore"));

        return buildPlayerHead(who, name, lore);
    }

    // --------------------
    // Static binder
    // --------------------

    private void bindStatic(Inventory inv, MenuHolder holder, int size, Player viewer, Team team,
                            ConfigurationSection items, String key, String overrideAction) {
        ConfigurationSection s = items.getConfigurationSection(key);
        if (s == null) return;

        int slot = Menus.clampSlot(s.getInt("slot", 0), size);

        Material mat = plugin.menus().material(s, "material", Material.STONE);
        String name = apply(viewer, team, plugin.menus().str(s, "name", "&fItem"));
        List<String> lore = applyList(viewer, team, plugin.menus().strList(s, "lore"));

        ItemStack it;
        if (mat == Material.PLAYER_HEAD) {
            UUID headUuid = (team == null ? null : team.getOwner());
            it = buildPlayerHead(headUuid, name, lore);
        } else {
            it = item(mat, name, lore);
        }

        inv.setItem(slot, it);

        String action = (overrideAction != null ? overrideAction : plugin.menus().str(s, "action", "NONE"));
        boolean close = plugin.menus().bool(s, "close", false);
        holder.bind(slot, action, close);
    }

    // --------------------
    // Placeholder apply
    // --------------------

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
        String members = (team == null ? "0" : String.valueOf(uniqueMemberCount(team)));

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

    // --------------------
    // Homes using your TeamHomeService
    // --------------------

    private List<String> getTeamHomeNames(Team team) {
        if (team == null) return List.of();
        if (plugin.teamHomes() == null) return List.of();

        List<TeamHome> homes = plugin.teamHomes().listHomes(team.getId());
        if (homes == null || homes.isEmpty()) return List.of();

        List<String> names = new ArrayList<>();
        for (TeamHome h : homes) {
            if (h == null) continue;
            if (h.getName() == null || h.getName().isBlank()) continue;
            names.add(h.getName());
        }
        return names;
    }

    // --------------------
    // Utils
    // --------------------

    private static UUID safeUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s.trim()); } catch (Exception ignored) { return null; }
    }

    private <T> List<T> slice(List<T> list, int start, int count) {
        if (list == null || list.isEmpty()) return List.of();
        if (start < 0) start = 0;
        if (start >= list.size()) return List.of();
        int end = Math.min(list.size(), start + count);
        return new ArrayList<>(list.subList(start, end));
    }

    private int uniqueMemberCount(Team t) {
        if (t == null) return 0;
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (t.getOwner() != null) set.add(t.getOwner());
        if (t.getMembers() != null) for (UUID u : t.getMembers()) if (u != null) set.add(u);
        return set.size();
    }

    private List<UUID> uniqueTeamMembers(Team team) {
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (team == null) return List.of();

        if (team.getOwner() != null) set.add(team.getOwner());
        if (team.getMembers() != null) for (UUID u : team.getMembers()) if (u != null) set.add(u);

        return new ArrayList<>(set);
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();

        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
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

    private ItemStack buildPlayerHead(UUID owningUuid, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();

        if (meta instanceof SkullMeta skull) {
            if (owningUuid != null) {
                OfflinePlayer off = Bukkit.getOfflinePlayer(owningUuid);
                if (off != null) skull.setOwningPlayer(off);
            }

            skull.setDisplayName(Msg.color(name == null ? "" : name));
            if (lore != null && !lore.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (String line : lore) out.add(Msg.color(line));
                skull.setLore(out);
            }

            skull.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(skull);
            return it;
        }

        return item(Material.PLAYER_HEAD, name, lore);
    }
}
