package dev.generative.equipment.paper.ui;

import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.generative.equipment.paper.*;
import dev.generative.equipment.paper.adapters.PaperWorldPorts;
import dev.generative.equipment.protocol.IntentPolicy;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** 原版箱子界面选择材料。意图从 /ge create 后自由输入，计数在提交前由同一协议校验。 */
public final class GenerationScreen implements Listener {
  private final EquipmentPlugin plugin;
  private final GameService game;
  private final PaperRuntimeController host;
  private final Map<UUID, Draft> drafts = new HashMap<>();

  private static final class Draft {
    Inventory inventory;
    JsonNode catalog;
    Map<Integer, JsonNode> materialSlots = new HashMap<>();
    String intent;
    Map<String, Integer> counts = new LinkedHashMap<>();
    boolean submitting;
  }

  public GenerationScreen(EquipmentPlugin plugin, GameService game, PaperRuntimeController host) {
    this.plugin = plugin;
    this.game = game;
    this.host = host;
  }

  public void open(Player player, String intent) {
    var checked = IntentPolicy.validate(intent);
    if (!checked.accepted()) {
      player.sendMessage("意图最多 10 个中文字符或英文单词；当前 " + checked.count() + "。请修改后再输入。");
      return;
    }
    if (!plugin.getConfig().getBoolean("generation.test-credits", true)) {
      player.sendMessage("当前版本尚未开放真实材料扣除。请在独立测试服启用 generation.test-credits。");
      return;
    }
    UUID id = player.getUniqueId();
    game.catalog()
        .whenComplete(
            (catalog, error) ->
                game.main(
                    () -> {
                      var p = Bukkit.getPlayer(id);
                      if (p == null) return null;
                      if (error != null) {
                        p.sendMessage("材料列表读取失败：" + game.rootMessage(error));
                        return null;
                      }
                      var draft = new Draft();
                      draft.intent = intent;
                      draft.catalog = catalog;
                      draft.inventory =
                          Bukkit.createInventory(null, 27, Component.text("生成装备 · 测试额度"));
                      drafts.put(id, draft);
                      draw(draft);
                      p.openInventory(draft.inventory);
                      p.sendMessage(
                          "本轮使用测试额度，不扣背包材料。点击材料增加，右键减少；绿色按钮确认生成。意图："
                              + (intent.isEmpty() ? "自由发挥" : intent));
                      return null;
                    }));
  }

  private ItemStack icon(Material type, String title, List<String> lines) {
    var item = new ItemStack(type);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(title));
    meta.lore(lines.stream().map(Component::text).toList());
    item.setItemMeta(meta);
    return item;
  }

  private void draw(Draft d) {
    d.inventory.clear();
    d.materialSlots.clear();
    int slot = 0;
    for (var material : d.catalog.path("materials")) {
      if (slot >= 18) break;
      String id = material.path("itemId").asText();
      var type = Material.matchMaterial(id);
      if (type == null) continue;
      d.materialSlots.put(slot, material);
      int count = d.counts.getOrDefault(id, 0);
      d.inventory.setItem(
          slot++,
          icon(
              type,
              material.path("name").asText() + " × " + count,
              List.of(material.path("gameFacts").asText(), "左键增加，右键减少")));
    }
    d.inventory.setItem(
        22,
        icon(
            Material.BOOK,
            "意图：" + (d.intent.isEmpty() ? "自由发挥" : d.intent),
            List.of("通过 /ge create <意图> 修改", "最多 10 个中文字符或英文单词")));
    d.inventory.setItem(24, icon(Material.BARRIER, "取消", List.of("不提交生成任务")));
    d.inventory.setItem(
        26, icon(Material.EMERALD, "确认生成", List.of("使用测试额度，不扣背包材料", "会调用真实模型；通过验证后才能领取")));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent event) {
    var d = drafts.get(event.getWhoClicked().getUniqueId());
    if (d == null || !event.getView().getTopInventory().equals(d.inventory)) return;
    event.setCancelled(true);
    if (d.submitting || !(event.getWhoClicked() instanceof Player p)) return;
    int slot = event.getRawSlot();
    if (slot == 24) {
      p.closeInventory();
      drafts.remove(p.getUniqueId());
      return;
    }
    if (slot == 26) {
      if (d.counts.isEmpty()) {
        p.sendMessage("请至少选择一种材料。");
        return;
      }
      d.submitting = true;
      var request = request(p, d);
      String task = request.path("taskId").asText();
      p.closeInventory();
      drafts.remove(p.getUniqueId());
      p.sendMessage("已提交生成请求：" + task + "。用 /ge tasks 查看进度，完成后 /ge claim " + task);
      game.message(
          p.getUniqueId(),
          game.submit(request).thenApply(result -> "生成任务状态：" + result.path("status").asText()));
      return;
    }
    if (d.materialSlots.containsKey(slot)) {
      var material = d.materialSlots.get(slot);
      String id = material.path("itemId").asText();
      int count = d.counts.getOrDefault(id, 0) + (event.isRightClick() ? -1 : 1);
      count =
          Math.max(
              0, Math.min(d.catalog.path("policy").path("maxCountPerMaterial").asInt(64), count));
      if (count == 0) d.counts.remove(id);
      else d.counts.put(id, count);
      draw(d);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent event) {
    var d = drafts.get(event.getWhoClicked().getUniqueId());
    if (d != null && event.getView().getTopInventory().equals(d.inventory))
      event.setCancelled(true);
  }

  private JsonNode request(Player player, Draft d) {
    var world = player.getWorld();
    var l = player.getLocation();
    ObjectNode r =
        MAPPER
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("taskId", UUID.randomUUID().toString())
            .put("hostId", host.profile().path("hostId").asText())
            .put("recipientPlayerId", player.getUniqueId().toString())
            .put("capabilityProfileId", host.profile().path("profileId").asText())
            .put("intentText", d.intent);
    r.set("worldRuleRef", d.catalog.path("worldRuleRef"));
    var materials = r.putArray("materials");
    d.counts.forEach(
        (id, count) -> materials.addObject().put("definitionId", id).put("count", count));
    r.putArray("sourceEquipment");
    var context = r.putObject("generationContext");
    context.put(
        "dimension",
        switch (world.getEnvironment()) {
          case NETHER -> "minecraft:the_nether";
          case THE_END -> "minecraft:the_end";
          default -> "minecraft:overworld";
        });
    context.put("biome", l.getBlock().getBiome().getKey().toString());
    context.set("position", PaperWorldPorts.position(l));
    context.put("worldTimeTicks", world.getFullTime());
    context.putObject("weather").put("worldRain", world.hasStorm()).putNull("exposedToRain");
    var near = context.putObject("nearbyBlocks").put("samplingVersion", "cube-1");
    var counts = new TreeMap<String, Integer>();
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++) {
          var pos = l.clone().add(x, y, z);
          if (world.isChunkLoaded(pos.getBlockX() >> 4, pos.getBlockZ() >> 4))
            counts.merge(pos.getBlock().getType().getKey().toString(), 1, Integer::sum);
        }
    var samples = near.putArray("samples");
    counts.forEach((block, count) -> samples.addObject().put("blockId", block).put("count", count));
    near.putNull("missingReason");
    return r;
  }
}
