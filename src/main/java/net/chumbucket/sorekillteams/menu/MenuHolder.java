package net.chumbucket.sorekillteams.menu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class MenuHolder implements InventoryHolder {

    private final String menuKey;
    private final Player viewer;

    // slot -> left action string (e.g., "OPEN:main", "COMMAND:team accept X", etc.)
    private final Map<Integer, String> leftActionsBySlot = new HashMap<>();

    // slot -> right action string (optional)
    private final Map<Integer, String> rightActionsBySlot = new HashMap<>();

    // slot -> close flag
    private final Map<Integer, Boolean> closeBySlot = new HashMap<>();

    private Inventory inv;

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

    /** Backwards-compatible bind: sets only left action */
    public void bind(int slot, String action, boolean close) {
        bind(slot, action, null, close);
    }

    /** Full bind: sets left + right actions */
    public void bind(int slot, String leftAction, String rightAction, boolean close) {
        if (leftAction != null) leftActionsBySlot.put(slot, leftAction);
        if (rightAction != null) rightActionsBySlot.put(slot, rightAction);
        closeBySlot.put(slot, close);
    }

    public String leftActionAt(int slot) { return leftActionsBySlot.get(slot); }
    public String rightActionAt(int slot) { return rightActionsBySlot.get(slot); }

    public boolean closeAt(int slot) { return closeBySlot.getOrDefault(slot, false); }
}
