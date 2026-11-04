package dev.generative.equipment.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.protocol.JsonContracts;
import java.util.*;

/** 检查展开后的表现成本和素材，而不只检查 JSON 字段。验证器与正式宿主共用。 */
public final class VisualPolicy {
  private VisualPolicy() {}

  public static final Set<String> PARTICLES =
      Set.of(
          "minecraft:end_rod",
          "minecraft:flame",
          "minecraft:cloud",
          "minecraft:happy_villager",
          "minecraft:enchant");
  public static final Set<String> SOUNDS =
      Set.of(
          "minecraft:block.amethyst_block.chime",
          "minecraft:entity.experience_orb.pickup",
          "minecraft:block.beacon.activate",
          "minecraft:entity.enderman.teleport");

  public static void validate(JsonNode frame) {
    JsonContracts.validate("VisualFrame", frame);
    int particles = 0, displays = 0, sounds = 0;
    Set<String> keys = new HashSet<>();
    for (var p : frame.path("primitives")) {
      if (!keys.add(p.path("key").asText()))
        throw new IllegalArgumentException("Duplicate visual key");
      switch (p.path("kind").asText()) {
        case "particle" -> {
          if (!PARTICLES.contains(p.path("particleKey").asText()) || p.has("options"))
            throw new IllegalArgumentException("Unsupported particle or options");
          particles += p.path("points").size() * p.path("countPerPoint").asInt();
        }
        case "sound" -> {
          sounds++;
          if (!SOUNDS.contains(p.path("soundKey").asText())
              || p.path("volume").asDouble() > 1
              || p.path("pitch").asDouble() < .5
              || p.path("pitch").asDouble() > 2)
            throw new IllegalArgumentException("Unsupported sound or volume");
        }
        case "text" -> {
          if (p.path("text").asText().length() > 160 || p.path("ttlTicks").asLong() > 40)
            throw new IllegalArgumentException("Text limit");
          if (p.path("channel").asText().equals("floating")) {
            displays++;
            if (!p.has("position"))
              throw new IllegalArgumentException("Floating text requires a position");
          }
        }
        case "display" -> {
          displays++;
          if (p.path("ttlTicks").asLong() > 40)
            throw new IllegalArgumentException("Display lifetime limit");
          for (String axis : List.of("x", "y", "z"))
            if (p.path("scale").path(axis).asDouble() <= 0
                || p.path("scale").path(axis).asDouble() > 2)
              throw new IllegalArgumentException("Display scale limit");
          double norm = 0;
          for (String axis : List.of("x", "y", "z", "w"))
            norm += Math.pow(p.path("rotation").path(axis).asDouble(), 2);
          if (Math.abs(norm - 1) > .02)
            throw new IllegalArgumentException("Rotation must be a unit quaternion");
        }
      }
    }
    if (particles > 128 || displays > 4 || sounds > 8)
      throw new IllegalArgumentException("Expanded visual budget exceeded");
  }
}
