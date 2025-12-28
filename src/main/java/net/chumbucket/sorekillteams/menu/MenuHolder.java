package net.chumbucket.sorekillteams.menu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MenuHolder implements InventoryHolder {

    private final String menuKey;
    private final Player viewer;

    // slot -> left/right action
    private final Map<Integer, String> leftActionsBySlot = new HashMap<>();
    private final Map<Integer, String> rightActionsBySlot = new HashMap<>();

    // slot -> close flag
    private final Map<Integer, Boolean> closeBySlot = new HashMap<>();

    // per-menu state (paging + confirm context)
    private int page = 0;
    private final Map<String, String> ctx = new HashMap<>();

    private Inventory inv;

    // ✅ NEW: slot -> teamId (for browse teams head cycling)
    private final Map<Integer, UUID> cyclingTeamBySlot = new HashMap<>();

    public MenuHolder(String menuKey, Player viewer) {
        this.menuKey = menuKey;
        this.viewer = viewer;
    }

    public String menuKey() { return menuKey; }
    public Player viewer() { return viewer; }

    public void setInventory(Inventory inv) { this.inv = inv; }

    @Override
    public Inventory getInventory() {
        return inv == null ? Bukkit.createInventory(this, 9) : inv;
    }

    public void clearBinds() {
        leftActionsBySlot.clear();
        rightActionsBySlot.clear();
        closeBySlot.clear();
        ctx.clear();
        cyclingTeamBySlot.clear();
    }

    /** Bind single action (left+right same). */
    public void bind(int slot, String action, boolean close) {
        if (action != null) {
            leftActionsBySlot.put(slot, action);
            rightActionsBySlot.put(slot, action);
        }
        closeBySlot.put(slot, close);
    }

    /** Bind left/right actions separately. */
    public void bind(int slot, String leftAction, String rightAction, boolean close) {
        if (leftAction != null) leftActionsBySlot.put(slot, leftAction);
        if (rightAction != null) rightActionsBySlot.put(slot, rightAction);
        closeBySlot.put(slot, close);
    }

    public String leftActionAt(int slot) { return leftActionsBySlot.get(slot); }
    public String rightActionAt(int slot) { return rightActionsBySlot.get(slot); }

    public boolean closeAt(int slot) { return closeBySlot.getOrDefault(slot, false); }

    // ---------- paging ----------
    public int page() { return page; }
    public void setPage(int page) { this.page = Math.max(0, page); }

    // ---------- context ----------
    public void ctxPut(String key, String value) {
        if (key == null) return;
        if (value == null) ctx.remove(key);
        else ctx.put(key, value);
    }

    public String ctxGet(String key) {
        if (key == null) return null;
        return ctx.get(key);
    }

    // ---------- browse-teams cycling ----------
    public void registerCyclingTeamSlot(int slot, UUID teamId) {
        if (teamId == null) return;
        cyclingTeamBySlot.put(slot, teamId);
    }

    public Map<Integer, UUID> cyclingTeamSlots() {
        return Collections.unmodifiableMap(cyclingTeamBySlot);
    }
}
