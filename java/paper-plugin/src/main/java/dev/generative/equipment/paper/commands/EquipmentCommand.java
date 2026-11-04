package dev.generative.equipment.paper.commands;

import dev.generative.equipment.paper.*;
import dev.generative.equipment.paper.adapters.PdcItemStateAdapter;
import dev.generative.equipment.paper.ui.GenerationScreen;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/** 玩家入口仅做权限与交互；耗时生成、下载、记录和交付由 GameService 在后台处理。 */
public final class EquipmentCommand implements CommandExecutor, TabCompleter {
  private final EquipmentPlugin plugin;
  private final PaperRuntimeController host;
  private final GameService game;
  private final GenerationScreen screen;

  public EquipmentCommand(
      EquipmentPlugin plugin,
      PaperRuntimeController host,
      GameService game,
      GenerationScreen screen) {
    this.plugin = plugin;
    this.host = host;
    this.game = game;
    this.screen = screen;
  }

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("ge.use")) {
      sender.sendMessage("没有 ge.use 权限。");
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage("/ge create [意图] | tasks | claim <任务编号> | inspect | records");
      return true;
    }
    if (args[0].equals("admin")) {
      if (!sender.hasPermission("ge.admin")) {
        sender.sendMessage("没有 ge.admin 权限。");
        return true;
      }
      if (args.length > 1 && args[1].equals("diagnose")) {
        sender.sendMessage(
            "QuickJS 已接通；定义 "
                + host.definitions().size()
                + "，活动 "
                + host.activeCount()
                + "。首次试玩使用测试额度。");
        game.diagnose()
            .whenComplete(
                (text, error) ->
                    game.main(
                        () -> {
                          sender.sendMessage(error == null ? text : game.rootMessage(error));
                          return null;
                        }));
        return true;
      }
      if (args.length > 2 && args[1].equals("load")) {
        game.load(args[2])
            .whenComplete(
                (def, error) ->
                    game.main(
                        () -> {
                          sender.sendMessage(
                              error == null
                                  ? "已热加载：" + def.equipment().path("name").asText()
                                  : game.rootMessage(error));
                          return null;
                        }));
        return true;
      }
      if (args.length > 3 && args[1].equals("disable")) {
        try {
          game.disable(args[2], Long.parseLong(args[3]));
          sender.sendMessage("定义已停用，活动已清理。");
        } catch (NumberFormatException e) {
          sender.sendMessage("版本号应为整数。");
        }
        return true;
      }
      sender.sendMessage("/ge admin diagnose | load <任务编号> | disable <定义编号> <版本>");
      return true;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage("此命令需要玩家执行。");
      return true;
    }
    switch (args[0]) {
      case "create" ->
          screen.open(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
      case "tasks" ->
          game.message(
              player.getUniqueId(),
              game.tasks(player.getUniqueId())
                  .thenApply(
                      tasks -> {
                        var lines = new ArrayList<String>();
                        for (var task : tasks)
                          lines.add(
                              task.path("taskId").asText()
                                  + " · "
                                  + task.path("status").asText()
                                  + (task.path("error").isNull()
                                      ? ""
                                      : " · " + task.path("error").path("message").asText()));
                        return lines.isEmpty() ? "还没有生成任务。" : String.join("\n", lines);
                      }));
      case "retry", "cancel" -> {
        if (args.length < 2) player.sendMessage("/ge " + args[0] + " <任务编号>");
        else
          game.message(
              player.getUniqueId(),
              args[0].equals("retry")
                  ? game.retry(args[1], player.getUniqueId())
                  : game.cancel(args[1], player.getUniqueId()));
      }
      case "claim" -> {
        if (args.length < 2) player.sendMessage("/ge claim <任务编号>");
        else game.message(player.getUniqueId(), game.claim(args[1], player.getUniqueId()));
      }
      case "inspect", "records" -> {
        var item = player.getInventory().getItemInMainHand();
        if (!PdcItemStateAdapter.generated(item)) {
          player.sendMessage("请把生成装备拿在主手。");
          break;
        }
        String id = PdcItemStateAdapter.text(item, "definition_id");
        long version = PdcItemStateAdapter.number(item, "definition_version");
        var def = host.definition(id, version);
        if (def == null) {
          player.sendMessage("定义尚未加载，当前不会执行能力。");
          break;
        }
        player.sendMessage(
            def.equipment().path("name").asText()
                + "\n"
                + def.equipment().path("description").asText());
        for (var c : def.equipment().path("constraints"))
          player.sendMessage(c.path("text").asText());
        if (args[0].equals("inspect")) {
          player.sendMessage(
              "定义 "
                  + id
                  + " v"
                  + version
                  + "\n物品状态："
                  + PdcItemStateAdapter.text(item, "state_json"));
        } else {
          var provenance = def.provenance();
          player.sendMessage(
              "来历："
                  + provenance.path("createdAt").asText()
                  + " · 意图："
                  + provenance.path("request").path("intentText").asText()
                  + " · 来源："
                  + provenance.path("origin").asText());
          game.message(
              player.getUniqueId(),
              game.records(id, version)
                  .thenApply(records -> "最近的宿主观察：" + records.path("items").toString()));
        }
      }
      default -> player.sendMessage("未知命令，输入 /ge 查看帮助。");
    }
    return true;
  }

  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    var choices =
        args.length == 1
            ? List.of("create", "tasks", "claim", "retry", "cancel", "inspect", "records", "admin")
            : args.length == 2 && args[0].equals("admin") && sender.hasPermission("ge.admin")
                ? List.of("diagnose", "load", "disable")
                : List.<String>of();
    String prefix = args.length == 0 ? "" : args[args.length - 1];
    return choices.stream()
        .filter(x -> x.startsWith(prefix))
        .filter(x -> !x.equals("admin") || sender.hasPermission("ge.admin"))
        .toList();
  }
}
