package dev.generative.equipment.core;

import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 自创字段可以任意命名；只允许有限的本地 schema 子集，不能解析远端引用或执行正则。 */
public final class StateSchema {
  private final JsonNode schema;
  private static final Set<String> KEYS =
      Set.of(
          "type",
          "properties",
          "required",
          "additionalProperties",
          "items",
          "minimum",
          "maximum",
          "minItems",
          "maxItems",
          "minLength",
          "maxLength",
          "enum",
          "description");

  public StateSchema(JsonNode schema) {
    checkSchema(schema, 0);
    this.schema = schema.deepCopy();
  }

  private static void checkSchema(JsonNode s, int depth) {
    if (!s.isObject() || depth > 12)
      throw new IllegalArgumentException("State schema must be a bounded local object");
    s.fieldNames()
        .forEachRemaining(
            k -> {
              if (!KEYS.contains(k))
                throw new IllegalArgumentException("Unsupported state schema keyword: " + k);
            });
    if (!Set.of("object", "array", "string", "number", "integer", "boolean", "null")
        .contains(s.path("type").asText()))
      throw new IllegalArgumentException("State schema requires a concrete type");
    if (s.path("type").asText().equals("object")) {
      if (!s.path("properties").isObject()
          || !s.path("additionalProperties").isBoolean()
          || s.path("additionalProperties").asBoolean())
        throw new IllegalArgumentException(
            "State objects require properties and additionalProperties:false");
      s.path("properties")
          .properties()
          .forEach(
              e -> {
                if (Set.of("__proto__", "constructor", "prototype").contains(e.getKey()))
                  throw new IllegalArgumentException("Reserved state key");
                checkSchema(e.getValue(), depth + 1);
              });
      for (var key : s.path("required"))
        if (!s.path("properties").has(key.asText()))
          throw new IllegalArgumentException("Unknown required state key");
    }
    if (s.path("type").asText().equals("array")) {
      if (!s.path("maxItems").canConvertToInt()
          || s.path("maxItems").asInt() < 0
          || s.path("maxItems").asInt() > 256)
        throw new IllegalArgumentException("State arrays require maxItems <=256");
      checkSchema(s.path("items"), depth + 1);
    }
    if (s.path("type").asText().equals("string")
        && !s.path("enum").isArray()
        && (!s.path("maxLength").canConvertToInt()
            || s.path("maxLength").asInt() < 0
            || s.path("maxLength").asInt() > 1024))
      throw new IllegalArgumentException("State strings require maxLength <=1024");
  }

  public void validate(JsonNode value) {
    try {
      if (MAPPER.writeValueAsBytes(value).length > 32768)
        throw new IllegalArgumentException("State exceeds 32 KiB");
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
    check(schema, value, 0);
  }

  private static void check(JsonNode s, JsonNode v, int depth) {
    if (v == null || depth > 16) throw new IllegalArgumentException("Invalid state depth");
    boolean valid =
        switch (s.path("type").asText()) {
          case "object" -> v.isObject();
          case "array" -> v.isArray();
          case "string" -> v.isTextual();
          case "number" -> v.isNumber() && Double.isFinite(v.asDouble());
          case "integer" ->
              v.isNumber()
                  && Double.isFinite(v.asDouble())
                  && v.asDouble() == Math.rint(v.asDouble());
          case "boolean" -> v.isBoolean();
          case "null" -> v.isNull();
          default -> false;
        };
    if (!valid) throw new IllegalArgumentException("State type mismatch");
    if (s.has("enum")) {
      boolean found = false;
      for (var x : s.path("enum")) if (x.equals(v)) found = true;
      if (!found) throw new IllegalArgumentException("State enum mismatch");
    }
    if (v.isObject()) {
      for (var k : s.path("required"))
        if (!v.has(k.asText()))
          throw new IllegalArgumentException("Missing state field " + k.asText());
      v.properties()
          .forEach(
              e -> {
                if (!s.path("properties").has(e.getKey()))
                  throw new IllegalArgumentException("Unknown state field " + e.getKey());
                check(s.path("properties").path(e.getKey()), e.getValue(), depth + 1);
              });
    } else if (v.isArray()) {
      bounds(s, v.size(), "minItems", "maxItems");
      for (var x : v) check(s.path("items"), x, depth + 1);
    } else if (v.isTextual())
      bounds(s, v.asText().codePointCount(0, v.asText().length()), "minLength", "maxLength");
    else if (v.isNumber()) bounds(s, v.asDouble(), "minimum", "maximum");
  }

  private static void bounds(JsonNode s, double n, String min, String max) {
    if (s.has(min) && n < s.path(min).asDouble() || s.has(max) && n > s.path(max).asDouble())
      throw new IllegalArgumentException("State value out of range");
  }
}
