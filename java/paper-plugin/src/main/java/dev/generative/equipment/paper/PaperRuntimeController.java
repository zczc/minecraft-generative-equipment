package dev.generative.equipment.paper;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.core.*;
import dev.generative.equipment.paper.adapters.*;
import dev.generative.equipment.protocol.*;
import dev.generative.equipment.runtime.QuickJsRuntime;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

/** Paper 主线程入口。调度、事件去重、槽位归属由宿主控制，装备自己的玩法留在 JS。 */
public final class PaperRuntimeController implements Listener, AutoCloseable {
  private final EquipmentPlugin plugin;
  private final QuickJsRuntime runtime;
  private final UUID sessionId = UUID.randomUUID();
  private final Map<String, LoadedDefinition> definitions = new HashMap<>();
  private final Set<String> disabled = new HashSet<>();
  private final Map<UUID, Session> sessions = new HashMap<>();
  private final Set<UUID> suspended = new HashSet<>();
  private long tick, usedNanos;
  private boolean enabled;
  private java.util.function.Consumer<JsonNode> observations = event -> {};

  public void observations(java.util.function.Consumer<JsonNode> value) {
    observations = value;
  }

  private static final class Session {
    ActivationLease lease;
    String fingerprint;
    RuleEngine engine;
    PaperWorldPorts world;
    PaperPresentationAdapter presentation;
    Location previous;
    long usedAt = -1;
  }

  public PaperRuntimeController(EquipmentPlugin plugin, QuickJsRuntime runtime) {
    this.plugin = plugin;
    this.runtime = runtime;
    enabled = plugin.getConfig().getBoolean("runtime.enabled", false);
  }

  public JsonNode profile() {
    return HostCapabilities.profile(
        plugin.getConfig().getString("host-id", "local-paper"), QuickJsRuntime.ENGINE);
  }

  public void enable() {
    enabled = true;
  }

  public long tick() {
    return tick;
  }

  public int activeCount() {
    return sessions.size();
  }

  public Collection<LoadedDefinition> definitions() {
    return List.copyOf(definitions.values());
  }

  public LoadedDefinition definition(String id, long version) {
    return definitions.get(id + ":" + version);
  }

  public void register(LoadedDefinition definition) {
    String key =
        definition.program().ref().definitionId() + ":" + definition.program().ref().version();
    var previous = definitions.get(key);
    if (previous != null
        && !previous.program().sourceHash().equals(definition.program().sourceHash()))
      throw new IllegalArgumentException("Immutable definition conflict");
    definitions.putIfAbsent(key, definition);
  }

  public void disable(String id, long version) {
    String key = id + ":" + version;
    disabled.add(key);
    for (var p : new ArrayList<>(sessions.keySet())) {
      var s = sessions.get(p);
      if ((s.lease.definition().definitionId() + ":" + s.lease.definition().version()).equals(key))
        stop(p);
    }
  }

  public boolean active(ActivationLease lease) {
    var s = sessions.get(lease.playerId());
    var p = Bukkit.getPlayer(lease.playerId());
    return enabled
        && s != null
        && s.lease.equals(lease)
        && p != null
        && p.isOnline()
        && !p.isDead()
        && !suspended.contains(p.getUniqueId())
        && s.fingerprint.equals(fingerprint(p));
  }

  private String fingerprint(Player p) {
    var item = p.getInventory().getItemInMainHand();
    return PdcItemStateAdapter.generated(item)
        ? p.getInventory().getHeldItemSlot()
            + ":"
            + PdcItemStateAdapter.reference(item)
            + ":"
            + PdcItemStateAdapter.text(item, "delivery_id")
            + ":"
            + item.getAmount()
        : "";
  }

  public void reconcile(Player p) {
    UUID id = p.getUniqueId();
    var s = sessions.get(id);
    if (s != null && !active(s.lease)) {
      stop(id);
      s = null;
    }
    if (!enabled || s != null || usedNanos >= 4_000_000 || p.isDead() || suspended.contains(id))
      return;
    var item = p.getInventory().getItemInMainHand();
    if (!PdcItemStateAdapter.generated(item) || item.getAmount() != 1) return;
    String key = PdcItemStateAdapter.reference(item);
    var definition = definitions.get(key);
    if (definition == null || disabled.contains(key)) return;
    var next = new Session();
    next.lease =
        new ActivationLease(
            sessionId, UUID.randomUUID(), id, "main_hand", tick, definition.program().ref());
    next.fingerprint = fingerprint(p);
    next.previous = p.getLocation();
    next.world = new PaperWorldPorts(id, () -> active(next.lease));
    next.world.tick(tick);
    next.world.observer(
        receipt -> {
          var event =
              MAPPER
                  .createObjectNode()
                  .put("eventId", UUID.randomUUID().toString())
                  .put("codeHash", definition.program().sourceHash())
                  .put("activationId", next.lease.activationId().toString())
                  .putNull("activityId")
                  .put("kind", "operation.receipt")
                  .put("observedAt", java.time.Instant.now().toString());
          event.set("definitionRef", MAPPER.valueToTree(definition.program().ref()));
          event.set("payload", MAPPER.valueToTree(receipt));
          observations.accept(event);
        });
    next.presentation = new PaperPresentationAdapter(plugin, runtime, definition, next.lease);
    next.presentation.tick(tick);
    next.engine =
        new RuleEngine(
            runtime,
            definition,
            next.lease,
            new PdcItemStateAdapter(this::active),
            next.world,
            next.world,
            () -> active(next.lease),
            () -> next.world.snapshot(p),
            next.presentation);
    next.presentation.engine(next.engine);
    sessions.put(id, next);
    dispatch(
        next, "item.activated", "internal", MAPPER.createObjectNode().put("slot", "main_hand"));
  }

  public void stop(UUID player) {
    var s = sessions.remove(player);
    if (s != null) s.engine.stop();
  }

  private void dispatch(Session s, String kind, String phase, JsonNode payload) {
    if (usedNanos >= 5_000_000) return;
    long start = System.nanoTime();
    s.engine.budgetNanos(5_000_000 - usedNanos);
    try {
      s.engine.onEvent(
          s.lease,
          new Contracts.RuleEvent(UUID.randomUUID().toString(), kind, tick, phase, payload));
    } catch (Exception e) {
      disable(s.lease.definition().definitionId(), s.lease.definition().version());
      var p = Bukkit.getPlayer(s.lease.playerId());
      if (p != null) p.sendMessage("这件装备运行失败，已停止能力并保留物品状态。管理员可查看日志。");
      plugin
          .getLogger()
          .warning(
              "Rule disabled: " + s.lease.definition() + " (" + e.getClass().getSimpleName() + ")");
    } finally {
      usedNanos += System.nanoTime() - start;
    }
  }

  /** 一刻总预算包含全部玩家；达到预算后跳过非即时更新，不让每件装备各拿一份无限预算。 */
  public void advance() {
    tick++;
    usedNanos = 0;
    for (var p : Bukkit.getOnlinePlayers()) reconcile(p);
    var ordered = new ArrayList<>(sessions.values());
    if (!ordered.isEmpty()) Collections.rotate(ordered, (int) (tick % ordered.size()));
    for (var s : ordered) {
      if (!active(s.lease)) {
        stop(s.lease.playerId());
        continue;
      }
      s.world.tick(tick);
      s.presentation.tick(tick);
      if (usedNanos >= 5_000_000) continue;
      long start = System.nanoTime();
      s.engine.budgetNanos(5_000_000 - usedNanos);
      try {
        s.engine.advance(tick);
      } catch (Exception e) {
        disable(s.lease.definition().definitionId(), s.lease.definition().version());
      }
      usedNanos += System.nanoTime() - start;
      var player = Bukkit.getPlayer(s.lease.playerId());
      if (player == null) continue;
      if (tick % 2 == 0 && s.previous.distanceSquared(player.getLocation()) > .0001) {
        var move = MAPPER.createObjectNode();
        move.set("from", PaperWorldPorts.position(s.previous));
        move.set("to", PaperWorldPorts.position(player.getLocation()));
        s.previous = player.getLocation();
        dispatch(s, "actor.move.sampled", "observed", move);
      }
      if (usedNanos < 5_000_000) {
        start = System.nanoTime();
        s.presentation.render(tick, start + 5_000_000 - usedNanos);
        usedNanos += System.nanoTime() - start;
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void use(PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND
        || !event.getAction().isRightClick()
        || event.useItemInHand() == Event.Result.DENY) return;
    var p = event.getPlayer();
    reconcile(p);
    var s = sessions.get(p.getUniqueId());
    if (s == null || s.usedAt == tick) return;
    s.usedAt = tick;
    event.setUseItemInHand(Event.Result.DENY);
    dispatch(
        s,
        "item.used",
        "internal",
        MAPPER.createObjectNode().put("action", "right_click").put("hand", "main_hand"));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void damage(EntityDamageByEntityEvent event) {
    if (!(event.getDamager() instanceof Player p)) return;
    reconcile(p);
    var s = sessions.get(p.getUniqueId());
    if (s == null) return;
    String handle = UUID.randomUUID().toString();
    s.world.damageEvent(event, handle);
    try {
      dispatch(
          s,
          "combat.damage.prepare",
          "prepare",
          MAPPER
              .createObjectNode()
              .put("eventHandle", handle)
              .put("target", s.world.ref(event.getEntity()))
              .put("healthPoints", event.getDamage()));
    } finally {
      s.world.clearDamage();
    }
  }

  @EventHandler
  public void held(PlayerItemHeldEvent e) {
    stop(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void swap(PlayerSwapHandItemsEvent e) {
    stop(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void drop(PlayerDropItemEvent e) {
    stop(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    stop(e.getPlayer().getUniqueId());
    suspended.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    stop(e.getEntity().getUniqueId());
  }

  @EventHandler
  public void teleport(PlayerTeleportEvent e) {
    stop(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void open(InventoryOpenEvent e) {
    suspended.add(e.getPlayer().getUniqueId());
    stop(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void closeInventory(InventoryCloseEvent e) {
    suspended.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    stop(e.getWhoClicked().getUniqueId());
    // 这些转换尚未验证 PDC 继承，因此拒绝处理生成物品；普通背包、箱子、丢弃和转交保留原版行为。
    if (Set.of(
            InventoryType.ANVIL,
            InventoryType.GRINDSTONE,
            InventoryType.SMITHING,
            InventoryType.CRAFTING,
            InventoryType.WORKBENCH)
        .contains(e.getInventory().getType())) {
      boolean found =
          PdcItemStateAdapter.generated(e.getCurrentItem())
              || PdcItemStateAdapter.generated(e.getCursor());
      for (var item : e.getInventory().getContents()) found |= PdcItemStateAdapter.generated(item);
      if (found
          && (e.getRawSlot() < e.getView().getTopInventory().getSize()
              || e.isShiftClick()
              || e.getClick() == ClickType.NUMBER_KEY)) e.setCancelled(true);
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    stop(e.getWhoClicked().getUniqueId());
    if (e.getInventory().getType() != InventoryType.CHEST
        && e.getInventory().getType() != InventoryType.PLAYER
        && PdcItemStateAdapter.generated(e.getOldCursor())) e.setCancelled(true);
  }

  public void close() {
    enabled = false;
    for (UUID p : new ArrayList<>(sessions.keySet())) stop(p);
  }
}
