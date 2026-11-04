package dev.generative.equipment.paper.listeners;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.entity.Player;
import dev.generative.equipment.core.ActivationRegistry;
/** 接入装备可能失活的原版事件。S0 只撤销内存凭据，资源清理在 S2 接入。 */
public final class InventoryLifecycleListener implements Listener {
    private final ActivationRegistry registry;
    public InventoryLifecycleListener(ActivationRegistry registry) { this.registry = registry; }
    private void stop(Player p) { registry.stopPlayer(p.getUniqueId(), lease -> {}); }
    @EventHandler public void quit(PlayerQuitEvent e) { stop(e.getPlayer()); }
    @EventHandler public void held(PlayerItemHeldEvent e) { stop(e.getPlayer()); }
    @EventHandler public void drop(PlayerDropItemEvent e) { stop(e.getPlayer()); }
    @EventHandler public void swap(PlayerSwapHandItemsEvent e) { stop(e.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent e) { stop(e.getEntity()); }
    @EventHandler public void inventory(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) stop(p); }
}
