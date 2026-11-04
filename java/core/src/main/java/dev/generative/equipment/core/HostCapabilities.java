package dev.generative.equipment.core;

import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 生成器、验证器和插件共用的能力清单；参数校验与实际执行分开，避免无效提案部分生效。 */
public final class HostCapabilities {
  public static final JsonNode DOCUMENT;
  public static final Set<String> BASE_ITEMS =
      Set.of(
          "minecraft:stick",
          "minecraft:blaze_rod",
          "minecraft:iron_sword",
          "minecraft:iron_pickaxe",
          "minecraft:feather",
          "minecraft:amethyst_shard");

  static {
    try (var in = HostCapabilities.class.getResourceAsStream("/capabilities/v1/host.json")) {
      DOCUMENT = MAPPER.readTree(Objects.requireNonNull(in));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private HostCapabilities() {}

  public static ObjectNode profile(String host, String engine) {
    var p =
        MAPPER
            .createObjectNode()
            .put("profileId", "paper-alpha1-" + host)
            .put("hostId", host)
            .put("hostBuildId", DOCUMENT.path("buildId").asText())
            .put("protocolVersion", "1")
            .put("engineBuildId", engine);
    for (String key : List.of("events", "queries", "operations", "visuals", "limits"))
      p.set(key, DOCUMENT.path(key).deepCopy());
    return p;
  }

  public static void equipment(JsonNode e, JsonNode profile) {
    if (!BASE_ITEMS.contains(e.path("baseItem").asText()))
      throw new IllegalArgumentException("Base item unavailable");
    if (e.path("activationSlots").size() != 1
        || !e.path("activationSlots").get(0).asText().equals("main_hand"))
      throw new IllegalArgumentException("Alpha supports main_hand only");
    for (var event : e.path("subscriptions"))
      if (!RuleEngine.contains(profile.path("events"), event.asText()))
        throw new IllegalArgumentException("Unsupported event " + event.asText());
    for (String kind : List.of("queries", "operations"))
      for (var name : e.path("requires").path(kind))
        if (!RuleEngine.contains(profile.path(kind), name.asText()))
          throw new IllegalArgumentException("Unsupported capability " + name.asText());
    new StateSchema(e.path("stateSchema")).validate(e.path("initialState"));
    for (var b : e.path("presentation").path("bindings")) {
      if (!e.path("presentation").path("cues").has(b.path("cueId").asText()))
        throw new IllegalArgumentException("Unknown binding cue");
      var boundCue = e.path("presentation").path("cues").path(b.path("cueId").asText());
      if (boundCue.path("category").asText().equals("result")
          && !b.path("trigger").asText().equals("operation.receipt"))
        throw new IllegalArgumentException("Result cue binding requires an operation receipt");
      for (String key : iterable(b.path("match").fieldNames()))
        if (!Set.of("phase", "capability", "status").contains(key))
          throw new IllegalArgumentException("Unsupported binding match");
    }
    e.path("presentation")
        .path("cues")
        .properties()
        .forEach(
            entry -> {
              var cue = entry.getValue();
              if (!cue.has("renderer") && !cue.has("recipe"))
                throw new IllegalArgumentException("Cue needs recipe or render");
              if (cue.path("category").asText().equals("result")
                  && cue.path("requiredReceiptStatus").isEmpty())
                throw new IllegalArgumentException(
                    "Result cue "
                        + entry.getKey()
                        + " must declare requiredReceiptStatus e.g. [APPLIED], and result feedback"
                        + " must use receiptIds from operation.results");
              if (cue.has("recipe"))
                dev.generative.equipment.protocol.JsonContracts.validate(
                    "VisualFrame", cue.path("recipe"));
            });
  }

  private static <T> Iterable<T> iterable(Iterator<T> i) {
    return () -> i;
  }

  public static void operation(String name, JsonNode args) {
    switch (name) {
      case "entity.velocity.add" -> {
        fields(args, Set.of("target", "deltaVelocity"));
        text(args, "target");
        var v = args.path("deltaVelocity");
        fields(v, Set.of("x", "y", "z"));
        double n = 0;
        for (String k : List.of("x", "y", "z")) {
          number(v, k, -1.5, 1.5);
          n += Math.pow(v.path(k).asDouble(), 2);
        }
        if (n > 2.25) throw new IllegalArgumentException("Velocity magnitude exceeded");
      }
      case "combat.damage.set" -> {
        fields(args, Set.of("eventHandle", "healthPoints"));
        text(args, "eventHandle");
        number(args, "healthPoints", 0, 20);
      }
      case "entity.damage.request" -> {
        fields(args, Set.of("target", "healthPoints"));
        text(args, "target");
        number(args, "healthPoints", 0, 8);
      }
      default -> throw new IllegalArgumentException("Unsupported operation");
    }
  }

  private static void fields(JsonNode args, Set<String> keys) {
    if (!args.isObject() || args.size() != keys.size())
      throw new IllegalArgumentException("Operation argument fields mismatch");
    args.fieldNames()
        .forEachRemaining(
            k -> {
              if (!keys.contains(k))
                throw new IllegalArgumentException("Unknown operation argument");
            });
  }

  private static void text(JsonNode a, String k) {
    if (!a.path(k).isTextual() || a.path(k).asText().isEmpty() || a.path(k).asText().length() > 128)
      throw new IllegalArgumentException("Invalid reference");
  }

  private static void number(JsonNode a, String k, double min, double max) {
    if (!a.path(k).isNumber()
        || !Double.isFinite(a.path(k).asDouble())
        || a.path(k).asDouble() < min
        || a.path(k).asDouble() > max)
      throw new IllegalArgumentException("Operation number out of range");
  }
}
