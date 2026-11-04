package dev.generative.equipment.paper.adapters;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.generative.equipment.core.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 表现只读取已接受的阶段。每个 cue 管理自己的声音去重与展示实体，结束时统一清理。 */
public final class PaperPresentationAdapter implements RuleEngine.Feedback {
  private final HostPorts.RuleRuntime runtime;
  private final org.bukkit.plugin.Plugin plugin;
  private final LoadedDefinition def;
  private final ActivationLease lease;
  private RuleEngine engine;
  private long tick;
  private final Map<String, Cue> cues = new LinkedHashMap<>();
  private static final Set<String> PARTICLES =
      Set.of(
          "minecraft:end_rod",
          "minecraft:flame",
          "minecraft:cloud",
          "minecraft:happy_villager",
          "minecraft:enchant");
  private static final Set<String> SOUNDS =
      Set.of(
          "minecraft:block.amethyst_block.chime",
          "minecraft:entity.experience_orb.pickup",
          "minecraft:block.beacon.activate",
          "minecraft:entity.enderman.teleport");

  private static final class Cue {
    String id, activity;
    JsonNode spec, data;
    long start;
    List<JsonNode> receipts;
    Set<String> sounds = new HashSet<>();
    Map<String, Entity> entities = new HashMap<>();
    Map<String, Long> expiry = new HashMap<>();
    Map<String, Set<UUID>> viewers = new HashMap<>();
  }

  public PaperPresentationAdapter(
      org.bukkit.plugin.Plugin plugin,
      HostPorts.RuleRuntime runtime,
      LoadedDefinition def,
      ActivationLease lease) {
    this.plugin = plugin;
    this.runtime = runtime;
    this.def = def;
    this.lease = lease;
  }

  public void engine(RuleEngine value) {
    engine = value;
  }

  public void tick(long value) {
    tick = value;
  }

  public void show(JsonNode signal, List<JsonNode> receipts) {
    var spec =
        def.equipment().path("presentation").path("cues").path(signal.path("cueId").asText());
    if (spec.isMissingNode() || cues.size() >= 16) return;
    if (spec.path("category").asText().equals("result")
        && receipts.stream()
            .noneMatch(
                r ->
                    RuleEngine.contains(
                        spec.path("requiredReceiptStatus"), r.path("status").asText()))) return;
    Cue cue = new Cue();
    cue.id = signal.path("cueId").asText();
    cue.activity = signal.path("activityKey").asText("");
    cue.spec = spec;
    cue.data = signal.path("data").isObject() ? signal.path("data") : MAPPER.createObjectNode();
    cue.start = tick;
    cue.receipts = List.copyOf(receipts);
    cues.put(UUID.randomUUID().toString(), cue);
  }

  public void stop(String activityKey) {
    var iterator = cues.values().iterator();
    while (iterator.hasNext()) {
      var cue = iterator.next();
      if (activityKey == null || cue.activity.equals(activityKey)) {
        clear(cue);
        iterator.remove();
      }
    }
    if (activityKey == null) {
      var p = Bukkit.getPlayer(lease.playerId());
      if (p != null) p.sendActionBar(Component.empty());
    }
  }

  private void clear(Cue cue) {
    cue.entities.values().forEach(Entity::remove);
    cue.entities.clear();
  }

  /** 所有绘制、序列化和清理共用调用方剩余预算；表现失败不改写已发生的游戏效果。 */
  public void render(long now, long deadline) {
    tick = now;
    var player = Bukkit.getPlayer(lease.playerId());
    if (player == null) {
      stop(null);
      return;
    }
    var iterator = cues.values().iterator();
    int particles = 0;
    while (iterator.hasNext()) {
      var cue = iterator.next();
      if (now - cue.start >= cue.spec.path("durationTicks").asLong()) {
        clear(cue);
        iterator.remove();
        continue;
      }
      if (!cue.activity.isEmpty() && !hasActivity(cue.activity)) {
        clear(cue);
        iterator.remove();
        continue;
      }
      var expired =
          cue.expiry.entrySet().stream()
              .filter(e -> e.getValue() <= now)
              .map(Map.Entry::getKey)
              .toList();
      for (String key : expired) {
        var entity = cue.entities.remove(key);
        if (entity != null) entity.remove();
        cue.expiry.remove(key);
      }
      if (System.nanoTime() >= deadline) break;
      try {
        JsonNode visuals;
        if (cue.spec.has("renderer")) {
          ObjectNode frame =
              MAPPER
                  .createObjectNode()
                  .put("cueId", cue.id)
                  .put("elapsedTicks", now - cue.start)
                  .putNull("progress");
          frame.set("activity", activity(cue.activity));
          frame.putObject("anchors").set("actor", PaperWorldPorts.position(player.getLocation()));
          frame.set("receipts", MAPPER.valueToTree(cue.receipts));
          frame.set("data", cue.data);
          var result =
              runtime.render(
                  def.program(),
                  new RenderInvocation(
                      lease, engine.state(), def.equipment().path("parameters"), frame, now),
                  new ExecutionBudget(8388608, deadline, 0, 0));
          if (!result.ok()) continue;
          visuals = result.frame();
        } else visuals = cue.spec.path("recipe");
        VisualPolicy.validate(visuals);
        for (var primitive : visuals.path("primitives")) {
          if (System.nanoTime() >= deadline) break;
          String kind = primitive.path("kind").asText(), key = primitive.path("key").asText();
          switch (kind) {
            case "particle" -> {
              String particle = primitive.path("particleKey").asText();
              int count = primitive.path("countPerPoint").asInt();
              if (!PARTICLES.contains(particle) || primitive.has("options")) continue;
              int total = count * primitive.path("points").size();
              if (particles + total > 128) continue;
              particles += total;
              Particle type = Particle.valueOf(particle.substring(10).toUpperCase(Locale.ROOT));
              for (var pos : primitive.path("points")) {
                Location l = location(player, pos);
                if (l == null) continue;
                for (var watcher : audience(player, cue))
                  watcher.spawnParticle(type, l, count, 0, 0, 0, 0);
              }
            }
            case "sound" -> {
              String sound = primitive.path("soundKey").asText();
              Location l = location(player, primitive.path("position"));
              if (l == null
                  || !SOUNDS.contains(sound)
                  || cue.sounds.size() >= 8
                  || !cue.sounds.add(key)) continue;
              float volume = (float) Math.min(1, primitive.path("volume").asDouble()),
                  pitch = (float) Math.max(.5, Math.min(2, primitive.path("pitch").asDouble()));
              for (var watcher : audience(player, cue)) watcher.playSound(l, sound, volume, pitch);
            }
            case "text" -> {
              String text = primitive.path("text").asText();
              if (text.length() > 160) continue;
              if (primitive.path("channel").asText().equals("actionbar"))
                player.sendActionBar(Component.text(text));
              else {
                Location l = location(player, primitive.path("position"));
                if (l == null) continue;
                Entity entity = display(cue, key, l, TextDisplay.class, player);
                if (entity instanceof TextDisplay display) {
                  display.text(Component.text(text));
                  cue.expiry.put(key, now + Math.min(40, primitive.path("ttlTicks").asLong()));
                }
              }
            }
            case "display" -> {
              Location l = location(player, primitive.path("position"));
              if (l == null) continue;
              var resource = primitive.path("resource");
              Material mat = Material.matchMaterial(resource.path("id").asText());
              if (mat == null || mat.isAir()) continue;
              var scale = primitive.path("scale");
              float x = (float) scale.path("x").asDouble(),
                  y = (float) scale.path("y").asDouble(),
                  z = (float) scale.path("z").asDouble();
              if (x <= 0 || y <= 0 || z <= 0 || x > 2 || y > 2 || z > 2) continue;
              var rot = primitive.path("rotation");
              Quaternionf q =
                  new Quaternionf(
                      (float) rot.path("x").asDouble(),
                      (float) rot.path("y").asDouble(),
                      (float) rot.path("z").asDouble(),
                      (float) rot.path("w").asDouble());
              if (Math.abs(q.lengthSquared() - 1) > .02) continue;
              Entity entity =
                  display(
                      cue,
                      key,
                      l,
                      resource.path("kind").asText().equals("block")
                          ? BlockDisplay.class
                          : ItemDisplay.class,
                      player);
              if (entity instanceof ItemDisplay d) d.setItemStack(new ItemStack(mat));
              else if (entity instanceof BlockDisplay d && mat.isBlock())
                d.setBlock(mat.createBlockData());
              if (entity instanceof Display d) {
                d.setTransformation(
                    new Transformation(
                        new Vector3f(), q, new Vector3f(x, y, z), new Quaternionf()));
                cue.expiry.put(key, now + Math.min(40, primitive.path("ttlTicks").asLong()));
              }
            }
          }
        }
      } catch (Exception ignored) {
        /* 单帧失败不扩大为世界回滚，也不把模型文本写入服务日志。 */
      }
    }
  }

  private Entity display(
      Cue cue, String key, Location location, Class<? extends Entity> type, Player actor) {
    Entity entity = cue.entities.get(key);
    if (entity != null && !type.isInstance(entity)) {
      entity.remove();
      cue.entities.remove(key);
      entity = null;
    }
    if (entity == null) {
      if (cues.values().stream().mapToInt(c -> c.entities.size()).sum() >= 4) return null;
      entity = location.getWorld().spawn(location, type);
      entity.setPersistent(false);
      entity.setVisibleByDefault(false);
      if (entity instanceof Display d) d.setViewRange(.5f);
      cue.entities.put(key, entity);
    } else entity.teleport(location);
    var current = audience(actor, cue);
    var ids = new HashSet<UUID>();
    for (var watcher : current) {
      ids.add(watcher.getUniqueId());
      watcher.showEntity(plugin, entity);
    }
    for (UUID id : cue.viewers.getOrDefault(key, Set.of()))
      if (!ids.contains(id)) {
        var watcher = Bukkit.getPlayer(id);
        if (watcher != null) watcher.hideEntity(plugin, entity);
      }
    cue.viewers.put(key, ids);
    return entity;
  }

  private List<Player> audience(Player actor, Cue cue) {
    if (cue.spec.path("audience").asText().equals("actor")) return List.of(actor);
    double distance = Math.min(32, cue.spec.path("maxDistance").asDouble(24));
    return actor.getWorld().getPlayers().stream()
        .filter(p -> p.getLocation().distanceSquared(actor.getLocation()) <= distance * distance)
        .limit(16)
        .toList();
  }

  private Location location(Player actor, JsonNode pos) {
    if (!pos.isObject()
        || !pos.path("worldId").asText().equals(actor.getWorld().getUID().toString())) return null;
    var l =
        new Location(
            actor.getWorld(),
            pos.path("x").asDouble(),
            pos.path("y").asDouble(),
            pos.path("z").asDouble());
    return Double.isFinite(l.getX())
            && Double.isFinite(l.getY())
            && Double.isFinite(l.getZ())
            && l.distanceSquared(actor.getLocation()) <= 1024
        ? l
        : null;
  }

  private JsonNode activity(String key) {
    for (var a : engine.activitySnapshot()) if (a.path("key").asText().equals(key)) return a;
    return NullNode.instance;
  }

  private boolean hasActivity(String key) {
    return !activity(key).isNull();
  }
}
