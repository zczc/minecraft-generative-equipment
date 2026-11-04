package dev.generative.equipment.paper.adapters;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.generative.equipment.core.*;
import dev.generative.equipment.protocol.Contracts;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

/** 主线程世界适配。实体引用由本次激活发行，模型不能拿任意 UUID 操作其他玩家。 */
public final class PaperWorldPorts implements HostPorts.QueryPort, HostPorts.WorldOperationPort {
  private final UUID player;
  private final Map<String, UUID> refs = new HashMap<>();
  private final java.util.function.BooleanSupplier active;
  private java.util.function.Consumer<Contracts.OperationReceipt> observer = r -> {};

  public void observer(java.util.function.Consumer<Contracts.OperationReceipt> value) {
    observer = value;
  }

  private long tick;
  private EntityDamageByEntityEvent damage;
  private String handle;

  public PaperWorldPorts(UUID player, java.util.function.BooleanSupplier active) {
    this.player = player;
    this.active = active;
    refs.put("self", player);
  }

  public void tick(long tick) {
    this.tick = tick;
  }

  public void damageEvent(EntityDamageByEntityEvent event, String handle) {
    this.damage = event;
    this.handle = handle;
  }

  public void clearDamage() {
    damage = null;
    handle = null;
  }

  public static ObjectNode position(Location l) {
    return MAPPER
        .createObjectNode()
        .put("worldId", l.getWorld().getUID().toString())
        .put("x", l.getX())
        .put("y", l.getY())
        .put("z", l.getZ());
  }

  public String ref(Entity entity) {
    for (var e : refs.entrySet()) if (e.getValue().equals(entity.getUniqueId())) return e.getKey();
    if (refs.size() >= 128) throw new IllegalArgumentException("Entity reference limit");
    String ref = "entity-" + UUID.randomUUID();
    refs.put(ref, entity.getUniqueId());
    return ref;
  }

  public ObjectNode snapshot(LivingEntity entity) {
    var n =
        MAPPER
            .createObjectNode()
            .put("ref", ref(entity))
            .put("health", entity.getHealth())
            .put("onGround", entity.isOnGround())
            .put("sneaking", entity instanceof Player p && p.isSneaking());
    n.set("position", position(entity.getLocation()));
    var v = entity.getVelocity();
    n.putObject("velocity").put("x", v.getX()).put("y", v.getY()).put("z", v.getZ());
    return n;
  }

  private Player actor() {
    if (!Bukkit.isPrimaryThread() || !active.getAsBoolean())
      throw new IllegalStateException("Inactive world scope");
    var p = Bukkit.getPlayer(player);
    if (p == null || !p.isOnline()) throw new IllegalStateException("Player offline");
    return p;
  }

  private LivingEntity target(String ref) {
    UUID id = refs.get(ref);
    Entity e = id == null ? null : Bukkit.getEntity(id);
    var p = actor();
    if (!(e instanceof LivingEntity living)
        || !living.isValid()
        || living.isDead()
        || living.getWorld() != p.getWorld()
        || living.getLocation().distanceSquared(p.getLocation()) > 256)
      throw new IllegalArgumentException("TARGET_GONE");
    return living;
  }

  public QueryResult query(String name, JsonNode args, ActivationLease lease) {
    try {
      var p = actor();
      JsonNode data;
      switch (name) {
        case "actor.status" -> data = snapshot(target(args.path("target").asText("self")));
        case "entity.nearby" -> {
          double radius = args.path("radius").asDouble(8);
          int limit = args.path("limit").asInt(8);
          if (radius < 0 || radius > 16 || limit < 1 || limit > 16)
            throw new IllegalArgumentException("QUERY_LIMIT");
          var out = MAPPER.createObjectNode();
          var list = out.putArray("entities");
          p.getWorld().getNearbyLivingEntities(p.getLocation(), radius).stream()
              .filter(e -> !e.getUniqueId().equals(player))
              .filter(e -> e.getLocation().distanceSquared(p.getLocation()) <= radius * radius)
              .sorted(
                  Comparator.comparingDouble(e -> e.getLocation().distanceSquared(p.getLocation())))
              .limit(limit)
              .forEach(e -> list.add(snapshot(e)));
          data = out;
        }
        case "view.raycast" -> {
          double distance = args.path("maxDistance").asDouble(12);
          if (distance < 0 || distance > 16) throw new IllegalArgumentException("QUERY_LIMIT");
          var hit =
              p.getWorld()
                  .rayTrace(
                      p.getEyeLocation(),
                      p.getEyeLocation().getDirection(),
                      distance,
                      FluidCollisionMode.NEVER,
                      true,
                      0.3,
                      e -> e instanceof LivingEntity && !e.getUniqueId().equals(player));
          var out = MAPPER.createObjectNode();
          out.set(
              "entity",
              hit != null && hit.getHitEntity() instanceof LivingEntity e
                  ? snapshot(e)
                  : NullNode.instance);
          out.set(
              "position",
              hit == null
                  ? NullNode.instance
                  : position(hit.getHitPosition().toLocation(p.getWorld())));
          if (hit != null && hit.getHitBlock() != null)
            out.put("block", hit.getHitBlock().getType().getKey().toString());
          else out.putNull("block");
          data = out;
        }
        case "world.block" -> {
          var pos = args.path("position");
          if (!pos.path("worldId").asText().equals(p.getWorld().getUID().toString()))
            throw new IllegalArgumentException("WORLD_MISMATCH");
          var l =
              new Location(
                  p.getWorld(),
                  pos.path("x").asDouble(),
                  pos.path("y").asDouble(),
                  pos.path("z").asDouble());
          if (!Double.isFinite(l.getX())
              || !Double.isFinite(l.getY())
              || !Double.isFinite(l.getZ())
              || l.distanceSquared(p.getLocation()) > 256)
            throw new IllegalArgumentException("QUERY_LIMIT");
          if (!p.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4))
            throw new IllegalArgumentException("CHUNK_UNAVAILABLE");
          data = MAPPER.createObjectNode().put("block", l.getBlock().getType().getKey().toString());
        }
        default -> throw new IllegalArgumentException("UNSUPPORTED_QUERY");
      }
      return new QueryResult(true, data, null);
    } catch (Exception e) {
      return new QueryResult(
          false,
          NullNode.instance,
          e instanceof IllegalArgumentException ? e.getMessage() : "QUERY_FAILED");
    }
  }

  public Contracts.OperationReceipt apply(
      Contracts.OperationRequest request, ActivationLease lease) {
    String status = "REJECTED", code = "OPERATION_REJECTED";
    var data = MAPPER.createObjectNode();
    try {
      HostCapabilities.operation(request.capability(), request.args());
      var p = actor();
      var a = request.args();
      switch (request.capability()) {
        case "entity.velocity.add" -> {
          var e = target(a.path("target").asText());
          if (e instanceof Player && !e.getUniqueId().equals(player))
            throw new IllegalArgumentException("PVP_DISABLED");
          var v = a.path("deltaVelocity");
          Vector next =
              e.getVelocity()
                  .add(
                      new Vector(
                          v.path("x").asDouble(), v.path("y").asDouble(), v.path("z").asDouble()));
          if (next.length() > 3) throw new IllegalArgumentException("VELOCITY_LIMIT");
          e.setVelocity(next);
          status = "APPLIED";
          code = "VELOCITY_SET";
        }
        case "combat.damage.set" -> {
          if (damage == null
              || damage.isCancelled()
              || !Objects.equals(handle, a.path("eventHandle").asText()))
            throw new IllegalArgumentException("PHASE_MISMATCH");
          if (damage.getEntity() instanceof Player)
            throw new IllegalArgumentException("PVP_DISABLED");
          damage.setDamage(a.path("healthPoints").asDouble());
          status = "PROPOSED";
          code = "DAMAGE_PROPOSED";
        }
        case "entity.damage.request" -> {
          var e = target(a.path("target").asText());
          if (e instanceof Player) throw new IllegalArgumentException("PVP_DISABLED");
          e.damage(a.path("healthPoints").asDouble(), p);
          status = "PROPOSED";
          code = "DAMAGE_REQUESTED";
        }
      }
    } catch (IllegalArgumentException e) {
      code = e.getMessage();
    } catch (Exception e) {
      status = "ERROR";
      code = "HOST_ERROR";
    }
    var receipt =
        new Contracts.OperationReceipt(
            UUID.randomUUID().toString(),
            request.key(),
            request.capability(),
            status,
            code,
            data,
            tick);
    observer.accept(receipt);
    return receipt;
  }
}
