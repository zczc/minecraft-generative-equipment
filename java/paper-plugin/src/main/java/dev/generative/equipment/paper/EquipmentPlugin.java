package dev.generative.equipment.paper;

import dev.generative.equipment.paper.commands.EquipmentCommand;
import dev.generative.equipment.paper.ui.GenerationScreen;
import dev.generative.equipment.protocol.JsonContracts;
import dev.generative.equipment.runtime.QuickJsRuntime;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/** 插件入口：启动时准备可信运行器与缓存；新定义注册不调用插件 reload，也不重启服务器。 */
public final class EquipmentPlugin extends JavaPlugin {
  private QuickJsRuntime runtime;
  private PaperRuntimeController host;
  private GameService game;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    try {
      JsonContracts.prewarm();
      runtime = new QuickJsRuntime();
      host = new PaperRuntimeController(this, runtime);
      game = new GameService(this, host, runtime);
      var screen = new GenerationScreen(this, game, host);
      var command = new EquipmentCommand(this, host, game, screen);
      var entry = Objects.requireNonNull(getCommand("ge"));
      entry.setExecutor(command);
      entry.setTabCompleter(command);
      getServer().getPluginManager().registerEvents(host, this);
      getServer().getPluginManager().registerEvents(screen, this);
      getServer().getScheduler().runTaskTimer(this, host::advance, 1, 1);
      game.start();
      getLogger()
          .info(
              "GenerativeEquipment alpha loaded. QuickJS enabled when runtime.enabled=true; new"
                  + " generation uses test credits.");
    } catch (Exception | LinkageError e) {
      getLogger()
          .severe(
              "GenerativeEquipment startup failed: "
                  + e.getClass().getSimpleName()
                  + ": "
                  + e.getMessage());
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  /** 先撤销活动和主线程任务，再结束后台编排，最后关闭运行器。 */
  @Override
  public void onDisable() {
    if (host != null) host.close();
    getServer().getScheduler().cancelTasks(this);
    if (game != null) game.close();
    if (runtime != null) runtime.close();
  }
}
