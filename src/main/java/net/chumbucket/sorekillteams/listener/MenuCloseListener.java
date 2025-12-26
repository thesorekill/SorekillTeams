package net.chumbucket.sorekillteams.listener;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.menu.MenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

public final class MenuCloseListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public MenuCloseListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (e.getInventory() == null) return;

        if (!(e.getInventory().getHolder() instanceof MenuHolder holder)) return;

        // Only stop cycling when team_info menu closes
        if ("team_info".equalsIgnoreCase(holder.menuKey())) {
            if (plugin.menuRouter() != null) {
                plugin.menuRouter().stopCycling(p.getUniqueId());
            }
        }
    }
}
