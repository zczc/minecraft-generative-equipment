package dev.generative.equipment.paper.commands;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import dev.generative.equipment.paper.EquipmentPlugin;
import dev.generative.equipment.paper.client.GeneratorClient;
import java.util.*;
/** 命令入口负责权限和玩家反馈；未实现的功能不消耗材料，也不伪造领取成功。 */
public final class EquipmentCommand implements CommandExecutor, TabCompleter {
    private final EquipmentPlugin plugin;
    public EquipmentCommand(EquipmentPlugin plugin) { this.plugin = plugin; }
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ge.use")) { sender.sendMessage("没有 ge.use 权限。"); return true; }
        if (args.length == 0) { sender.sendMessage("/ge create | tasks | claim | inspect | records | admin diagnose"); return true; }
        if (args[0].equals("admin")) {
            if (!sender.hasPermission("ge.admin")) { sender.sendMessage("没有 ge.admin 权限。"); return true; }
            if (args.length > 1 && args[1].equals("diagnose")) {
                sender.sendMessage("S0：插件已加载；QuickJS、动态装备与交付尚未实现。");
                // HTTP 在后台执行，回到主线程后才能访问玩家并发送结果。
                new GeneratorClient(plugin).diagnose().whenComplete((text,error) -> {
                    if (!plugin.isEnabled()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (sender instanceof Player p && !p.isOnline()) return;
                        sender.sendMessage(error == null ? text : "生成服务连接失败，请检查本机配置。");
                    });
                });
                return true;
            }
        }
        if (args[0].equals("inspect") && sender instanceof Player player) {
            var item = player.getInventory().getItemInMainHand();
            var meta = item.getItemMeta();
            String id = meta == null ? null : meta.getPersistentDataContainer().get(Objects.requireNonNull(NamespacedKey.fromString("ge:definition_id")), PersistentDataType.STRING);
            sender.sendMessage(id == null ? "当前物品不是生成装备。" : "定义：" + id + "；S0 不激活生成代码。");
            return true;
        }
        sender.sendMessage("NOT_IMPLEMENTED：此功能属于后续阶段；未消耗材料，未生成或发放装备。");
        return true;
    }
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        var choices = args.length == 1 ? List.of("create","tasks","claim","inspect","records","admin") : args.length == 2 && args[0].equals("admin") && sender.hasPermission("ge.admin") ? List.of("diagnose","load","disable") : List.<String>of();
        String prefix = args.length == 0 ? "" : args[args.length-1];
        return choices.stream().filter(x -> x.startsWith(prefix)).filter(x -> !x.equals("admin") || sender.hasPermission("ge.admin")).toList();
    }
}
