package dev.generative.equipment.paper.client;
import dev.generative.equipment.paper.EquipmentPlugin;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
/** 本机生成服务客户端；只异步请求，令牌仅在本机 HTTP 鉴权头中发送。 */
public final class GeneratorClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final EquipmentPlugin plugin;
    public GeneratorClient(EquipmentPlugin plugin) { this.plugin = plugin; }
    public CompletableFuture<String> diagnose() {
        String token = System.getenv(plugin.getConfig().getString("generator.token-env", "GE_INTERNAL_TOKEN"));
        if (token == null || token.length() < 32) return CompletableFuture.completedFuture("请配置 GE_INTERNAL_TOKEN（至少 32 字符）。");
        try {
            URI base = URI.create(plugin.getConfig().getString("generator.base-url", "http://127.0.0.1:8765"));
            if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getUserInfo() != null) return CompletableFuture.completedFuture("S0 生成服务仅支持本机 127.0.0.1。");
            var request = HttpRequest.newBuilder(base.resolve("/v1/health/ready")).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(r -> switch(r.statusCode()) {
                case 200 -> "生成服务报告已就绪；插件运行器仍为 S0。";
                case 503 -> "生成服务可连接，真实生成依赖尚未就绪（S0）。";
                case 401 -> "生成服务鉴权失败，请核对令牌。";
                default -> "生成服务返回 HTTP " + r.statusCode();
            });
        } catch (RuntimeException e) { return CompletableFuture.completedFuture("生成服务地址配置无效。"); }
    }
}
