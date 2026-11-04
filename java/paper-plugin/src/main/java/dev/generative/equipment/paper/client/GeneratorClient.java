package dev.generative.equipment.paper.client;

import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.paper.EquipmentPlugin;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** 内部 HTTP 客户端只在后台 executor 调用。令牌不进入日志，也不交给玩家或生成脚本。 */
public final class GeneratorClient {
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI base;
  private final String token;

  public GeneratorClient(EquipmentPlugin plugin) {
    base = URI.create(plugin.getConfig().getString("generator.base-url", "http://127.0.0.1:8765"));
    if (!"http".equals(base.getScheme())
        || !"127.0.0.1".equals(base.getHost())
        || base.getUserInfo() != null)
      throw new IllegalArgumentException("Generator must be a local HTTP service");
    token = System.getenv(plugin.getConfig().getString("generator.token-env", "GE_INTERNAL_TOKEN"));
  }

  public byte[] request(String method, String path, JsonNode body, String idempotency)
      throws Exception {
    if (org.bukkit.Bukkit.isPrimaryThread())
      throw new IllegalStateException("HTTP must run off main thread");
    if (token == null || token.length() < 32)
      throw new IllegalStateException("GE_INTERNAL_TOKEN 未配置");
    URI uri = base.resolve(path);
    if (!uri.getAuthority().equals(base.getAuthority()))
      throw new IllegalArgumentException("Cross-host download rejected");
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token);
    if (idempotency != null) builder.header("Idempotency-Key", idempotency);
    if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
    else
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body)));
    var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    byte[] bytes;
    try (var in = response.body()) {
      bytes = in.readNBytes(1048577);
    }
    if (bytes.length > 1048576) throw new IllegalArgumentException("HTTP response too large");
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String code = "HTTP_" + response.statusCode();
      try {
        var error = MAPPER.readTree(bytes);
        code = error.path("error").path("code").asText(code);
      } catch (Exception ignored) {
      }
      throw new IllegalStateException(code);
    }
    return bytes;
  }

  public JsonNode json(String method, String path, JsonNode body, String key) throws Exception {
    return MAPPER.readTree(request(method, path, body, key));
  }

  public CompletableFuture<String> diagnose() {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return "生成服务：" + json("GET", "/v1/health/ready", null, null).toString();
          } catch (Exception e) {
            return "生成服务未就绪：" + e.getMessage();
          }
        });
  }
}
