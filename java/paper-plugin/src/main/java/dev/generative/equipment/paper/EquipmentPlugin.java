package dev.generative.equipment.paper;
import org.bukkit.plugin.java.JavaPlugin;
import dev.generative.equipment.runtime.QuickJsRuntime;
import dev.generative.equipment.core.ActivationRegistry;
import dev.generative.equipment.paper.commands.EquipmentCommand;
import dev.generative.equipment.paper.listeners.InventoryLifecycleListener;
import java.util.Objects;
/** Paper 生命周期入口，只组装插件；S0 没有注册任何可执行装备规则。 */
public final class EquipmentPlugin extends JavaPlugin {
    private QuickJsRuntime runtime;
    private final ActivationRegistry activations = new ActivationRegistry();
    @Override public void onEnable() {
        saveDefaultConfig(); runtime = new QuickJsRuntime();
        var executor = new EquipmentCommand(this);
        var command = Objects.requireNonNull(getCommand("ge"));
        command.setExecutor(executor); command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new InventoryLifecycleListener(activations), this);
        getLogger().info("GenerativeEquipment S0 loaded. No rules or model generation are active.");
    }
    /** 先停止调度和激活，再释放运行器；这里没有已创建的持续游戏效果。 */
    @Override public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        activations.stopAll(lease -> {});
        if (runtime != null) runtime.close();
    }
}
