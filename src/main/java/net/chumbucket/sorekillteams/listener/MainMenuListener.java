package net.chumbucket.sorekillteams.listener;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.menu.MenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class MainMenuListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public MainMenuListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory() == null) return;

        if (!(e.getInventory().getHolder() instanceof MenuHolder holder)) return;

        // Only let the viewer click their own menu instance
        if (holder.viewer() == null || !holder.viewer().getUniqueId().equals(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        // Cancel ALL menu interaction (so they can’t take items)
        e.setCancelled(true);

        int rawSlot = e.getRawSlot();
        if (rawSlot < 0 || rawSlot >= e.getInventory().getSize()) return;

        String action;
        ClickType type = e.getClick();

        if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) {
            action = holder.rightActionAt(rawSlot);
            if (action == null) action = holder.leftActionAt(rawSlot);
        } else {
            action = holder.leftActionAt(rawSlot);
        }

        if (action == null || action.isBlank()) return;

        boolean close = holder.closeAt(rawSlot);

        if (plugin.menuRouter() != null) {
            plugin.menuRouter().runAction(p, action);
        }

        if (close) {
            // close one tick later so OPEN/COMMAND/FLOW can execute cleanly
            plugin.getServer().getScheduler().runTask(plugin, p::closeInventory);
        }
    }
}
