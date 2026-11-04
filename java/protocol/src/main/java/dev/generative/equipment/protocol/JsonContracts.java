package dev.generative.equipment.protocol;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import java.util.*;

/** Java 协议校验入口，读取生成的 schema，与 TypeScript 共享字段和边界。 */
public final class JsonContracts {
  public static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNode DEFINITIONS;
  private static final Map<String, JsonSchema> CACHE = new HashMap<>();

  static {
    MAPPER
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(1048576).build());
    try (var stream = JsonContracts.class.getResourceAsStream("/contracts/schemas.json")) {
      if (stream == null) throw new IllegalStateException("Missing generated contracts");
      DEFINITIONS = MAPPER.readTree(stream).get("definitions");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private JsonContracts() {}

  /** 启动或后台加载阶段编译 schema，避免第一次游戏事件承担编译成本。 */
  public static void prewarm() {
    DEFINITIONS
        .fieldNames()
        .forEachRemaining(
            name -> {
              try {
                validate(name, MAPPER.createObjectNode());
              } catch (IllegalArgumentException ignored) {
              }
            });
  }

  /** 校验器缓存只在此入口访问；调用方必须在转成业务 DTO 前完成校验。 */
  public static synchronized void validate(String name, JsonNode value) {
    boundary(value, 0);
    if (!DEFINITIONS.has(name)) throw new IllegalArgumentException("Unknown contract: " + name);
    JsonSchema schema =
        CACHE.computeIfAbsent(
            name,
            key -> {
              var root = MAPPER.createObjectNode();
              root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
              root.put("$ref", "#/$defs/" + key);
              root.set("$defs", DEFINITIONS);
              return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
            });
    var errors = schema.validate(value);
    if (!errors.isEmpty())
      throw new IllegalArgumentException("Contract validation failed: " + errors);
  }

  private static void boundary(JsonNode node, int depth) {
    if (node == null || depth > 32)
      throw new IllegalArgumentException("Invalid JSON depth or null node");
    if (node.isNumber() && !Double.isFinite(node.doubleValue()))
      throw new IllegalArgumentException("Non-finite number");
    if (node.isObject())
      node.properties()
          .forEach(
              e -> {
                if (Set.of("__proto__", "prototype", "constructor").contains(e.getKey()))
                  throw new IllegalArgumentException("Reserved JSON key");
                boundary(e.getValue(), depth + 1);
              });
    else if (node.isArray()) for (var child : node) boundary(child, depth + 1);
  }
}
