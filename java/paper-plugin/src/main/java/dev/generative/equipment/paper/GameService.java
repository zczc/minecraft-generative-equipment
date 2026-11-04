package dev.generative.equipment.paper;

import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.generative.equipment.core.*;
import dev.generative.equipment.paper.adapters.*;
import dev.generative.equipment.paper.client.GeneratorClient;
import dev.generative.equipment.paper.persistence.LocalJournal;
import dev.generative.equipment.protocol.JsonContracts;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;

/** 模型请求、缓存和交付的后台编排。所有库存变更通过主线程执行；普通装备使用不依赖服务在线。 */
public final class GameService implements AutoCloseable {
  private final EquipmentPlugin plugin;
  private final PaperRuntimeController host;
  private final GeneratorClient client;
  private final PackageLoader loader;
  private final dev.generative.equipment.runtime.QuickJsRuntime runtime;
  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final LocalJournal journal;
  private final Path cache;
  private final Set<String> claiming = ConcurrentHashMap.newKeySet();
  private final Set<CompletableFuture<?>> callbacks = ConcurrentHashMap.newKeySet();
  private final ArrayBlockingQueue<JsonNode> observations = new ArrayBlockingQueue<>(512);
  private volatile boolean closed;
  private long droppedObservations;

  public GameService(
      EquipmentPlugin plugin, PaperRuntimeController host, HostPorts.RuleRuntime runtime)
      throws Exception {
    this.plugin = plugin;
    this.runtime = (dev.generative.equipment.runtime.QuickJsRuntime) runtime;
    this.host = host;
    client = new GeneratorClient(plugin);
    loader = new PackageLoader(runtime, host.profile());
    cache = plugin.getDataFolder().toPath().resolve("definitions");
    Files.createDirectories(cache);
    journal = new LocalJournal(plugin.getDataFolder().toPath().resolve("journal"));
  }

  public <T> CompletableFuture<T> background(Callable<T> action) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return action.call();
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        executor);
  }

  public <T> CompletableFuture<T> main(Callable<T> action) {
    var result = new CompletableFuture<T>();
    if (closed || !plugin.isEnabled()) {
      result.completeExceptionally(new IllegalStateException("Plugin stopped"));
      return result;
    }
    callbacks.add(result);
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              try {
                if (closed) throw new IllegalStateException("Plugin stopped");
                result.complete(action.call());
              } catch (Exception e) {
                result.completeExceptionally(e);
              } finally {
                callbacks.remove(result);
              }
            });
    return result;
  }

  /** 先恢复已验证缓存，再握手。服务不可用不阻止缓存装备继续运行。 */
  public void start() {
    host.observations(
        event -> {
          if (!observations.offer(event)) droppedObservations++;
        });
    Bukkit.getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              if (!observations.isEmpty())
                background(
                    () -> {
                      var batch = MAPPER.createArrayNode();
                      for (int i = 0; i < 100; i++) {
                        var event = observations.poll();
                        if (event == null) break;
                        batch.add(event);
                      }
                      try {
                        client.json("POST", "/v1/observations/batch", batch, null);
                      } catch (Exception e) {
                        for (var event : batch)
                          if (!observations.offer(event)) droppedObservations++;
                      }
                      return null;
                    });
            },
            40,
            40);
    background(
        () -> {
          dev.generative.equipment.runtime.RuntimeWarmup.run(runtime);
          for (var entry : journal.entries()) {
            if (entry.path("kind").asText().equals("disabled"))
              main(() -> {
                    host.disable(
                        entry.path("definitionId").asText(), entry.path("version").asLong());
                    return null;
                  })
                  .join();
            if (entry.path("state").asText().equals("PREPARED")) {
              var next = (ObjectNode) entry;
              next.put("state", "RECOVERY_REQUIRED");
              journal.put("delivery:" + entry.path("taskId").asText(), next);
            }
            if (entry.path("state").asText().equals("APPLIED"))
              try {
                String task = entry.path("taskId").asText(),
                    delivery = entry.path("deliveryId").asText();
                deliveryEvent(task, delivery, 2, "APPLIED");
                deliveryEvent(task, delivery, 3, "ACKNOWLEDGED");
                ((ObjectNode) entry).put("state", "ACKNOWLEDGED");
                journal.put("delivery:" + task, entry);
              } catch (Exception ignored) {
              }
          }

          try (var files = Files.list(cache)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".view.json")).toList())
              try {
                var view = MAPPER.readTree(Files.readAllBytes(file));
                var zip =
                    Files.readAllBytes(
                        file.resolveSibling(
                            file.getFileName().toString().replace(".view.json", ".zip")));
                var def = loader.prepare(zip, view);
                main(() -> {
                      host.register(def);
                      return null;
                    })
                    .join();
              } catch (Exception e) {
                plugin
                    .getLogger()
                    .warning("Ignored invalid cached definition: " + file.getFileName());
              }
          }
          try {
            client.json(
                "POST",
                "/v1/hosts/" + enc(host.profile().path("hostId").asText()) + "/capability-profiles",
                host.profile(),
                null);
          } catch (Exception e) {
            plugin.getLogger().info("Generator not ready; cached definitions remain available.");
          }
          return null;
        });
  }

  public CompletableFuture<JsonNode> catalog() {
    return background(
        () ->
            client.json(
                "GET",
                "/v1/worlds/"
                    + enc(plugin.getConfig().getString("world-rule-id", "mist-isle"))
                    + "/materials",
                null,
                null));
  }

  public CompletableFuture<JsonNode> submit(JsonNode request) {
    return background(
        () -> {
          JsonContracts.validate("GenerationRequest", request);
          String task = request.path("taskId").asText();
          // 请求先保存在本地，网络超时可用原 taskId 重试，不能悄悄创建新付费请求。
          journal.put("request:" + task, request);
          client.json(
              "POST",
              "/v1/hosts/" + enc(request.path("hostId").asText()) + "/capability-profiles",
              host.profile(),
              null);
          return client.json("POST", "/v1/generations", request, task);
        });
  }

  public CompletableFuture<String> retry(String task, UUID player) {
    return background(
        () -> {
          var request = journal.get("request:" + task);
          if (request == null
              || !request.path("recipientPlayerId").asText().equals(player.toString()))
            throw new IllegalArgumentException("本机没有你的原始请求");
          return "任务状态："
              + client.json("POST", "/v1/generations", request, task).path("status").asText();
        });
  }

  public CompletableFuture<String> cancel(String task, UUID player) {
    return background(
        () -> {
          var current = client.json("GET", "/v1/generations/" + enc(task), null, null);
          if (!current.path("recipientPlayerId").asText().equals(player.toString()))
            throw new IllegalArgumentException("只能取消自己的任务");
          var request =
              MAPPER
                  .createObjectNode()
                  .put("requestId", UUID.randomUUID().toString())
                  .put("reason", "PLAYER_CANCELLED");
          client.json("POST", "/v1/generations/" + enc(task) + "/cancel", request, null);
          return "任务已取消。";
        });
  }

  public void disable(String id, long version) {
    host.disable(id, version);
    background(
        () -> {
          journal.put(
              "disabled:" + id + ":" + version,
              MAPPER
                  .createObjectNode()
                  .put("kind", "disabled")
                  .put("definitionId", id)
                  .put("version", version));
          return null;
        });
  }

  public CompletableFuture<JsonNode> tasks(UUID player) {
    return background(
        () ->
            client.json(
                "GET", "/v1/generations?recipientPlayerId=" + enc(player.toString()), null, null));
  }

  public CompletableFuture<JsonNode> task(String id) {
    return background(() -> client.json("GET", "/v1/generations/" + enc(id), null, null));
  }

  private LoadedDefinition loadBlocking(JsonNode task) throws Exception {
    if (!task.path("status").asText().equals("READY"))
      throw new IllegalStateException("任务尚未通过验证：" + task.path("status").asText());
    var ref = task.path("definitionRef");
    String path =
        "/v1/definitions/"
            + enc(ref.path("definitionId").asText())
            + "/versions/"
            + ref.path("version").asInt();
    var view = client.json("GET", path, null, null);
    byte[] zip = client.request("GET", view.path("downloadPath").asText(), null, null);
    var definition = loader.prepare(zip, view);
    String key = PackageLoader.sha(MAPPER.writeValueAsBytes(ref));
    Path bundle = cache.resolve(key + ".zip"), metadata = cache.resolve(key + ".view.json");
    Files.write(bundle, zip);
    Files.write(metadata, MAPPER.writeValueAsBytes(view));
    main(() -> {
          host.register(definition);
          return null;
        })
        .join();
    return definition;
  }

  public CompletableFuture<LoadedDefinition> load(String task) {
    return background(
        () -> loadBlocking(client.json("GET", "/v1/generations/" + enc(task), null, null)));
  }

  public CompletableFuture<String> claim(String taskId, UUID player) {
    if (!claiming.add(taskId)) return CompletableFuture.completedFuture("正在处理这次领取，请稍候。");
    return background(
            () -> {
              var previous = journal.get("delivery:" + taskId);
              if (previous != null && !previous.path("state").asText().equals("WAITING_PLAYER"))
                return "此次交付已有记录："
                    + previous.path("state").asText()
                    + "。不会重复发放；PREPARED 状态需要管理员核对。";
              var task = client.json("GET", "/v1/generations/" + enc(taskId), null, null);
              if (!task.path("recipientPlayerId").asText().equals(player.toString()))
                throw new IllegalArgumentException("只能领取自己的生成任务");
              var def = loadBlocking(task);
              String delivery = "delivery-" + taskId;
              boolean canReceive =
                  main(() -> {
                        var p = Bukkit.getPlayer(player);
                        return p != null && p.isOnline() && p.getInventory().firstEmpty() >= 0;
                      })
                      .join();
              if (!canReceive) return "请在线并留出一个背包空位后领取。";
              var record =
                  MAPPER
                      .createObjectNode()
                      .put("taskId", taskId)
                      .put("playerId", player.toString())
                      .put("deliveryId", delivery)
                      .put("state", "PREPARED");
              journal.put("delivery:" + taskId, record);
              deliveryEvent(taskId, delivery, 1, "PREPARED");
              boolean applied =
                  main(() -> {
                        var p = Bukkit.getPlayer(player);
                        if (p == null || !p.isOnline()) return false;
                        int slot = p.getInventory().firstEmpty();
                        if (slot < 0) return false;
                        p.getInventory().setItem(slot, PdcItemStateAdapter.create(def, delivery));
                        return true;
                      })
                      .join();
              if (!applied) {
                record.put("state", "WAITING_PLAYER");
                journal.put("delivery:" + taskId, record);
                return "领取前玩家离线或背包已满，请稍后重试。";
              }
              record.put("state", "APPLIED");
              journal.put("delivery:" + taskId, record);
              try {
                deliveryEvent(taskId, delivery, 2, "APPLIED");
                deliveryEvent(taskId, delivery, 3, "ACKNOWLEDGED");
                record.put("state", "ACKNOWLEDGED");
                journal.put("delivery:" + taskId, record);
              } catch (Exception e) {
                return "装备已放入背包，远端交付确认待恢复；请勿重复领取。";
              }
              return "已领取：" + def.equipment().path("name").asText() + "。放到主手后右键使用。";
            })
        .whenComplete((r, e) -> claiming.remove(taskId));
  }

  private void deliveryEvent(String task, String delivery, int sequence, String state)
      throws Exception {
    var event =
        MAPPER
            .createObjectNode()
            .put("eventId", delivery + ":" + sequence)
            .put("deliveryId", delivery)
            .put("hostId", host.profile().path("hostId").asText())
            .put("taskId", task)
            .put("sequenceNo", sequence)
            .put("state", state);
    event.putObject("evidence").put("source", "paper-inventory-observation");
    client.json(
        "POST",
        "/v1/generations/" + enc(task) + "/delivery-events",
        event,
        event.path("eventId").asText());
  }

  public void message(UUID player, CompletableFuture<String> result) {
    result.whenComplete(
        (text, error) ->
            main(
                () -> {
                  var p = Bukkit.getPlayer(player);
                  if (p != null)
                    p.sendMessage(error == null ? text : "操作未完成：" + rootMessage(error));
                  return null;
                }));
  }

  public String rootMessage(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    String message = e.getMessage();
    return message == null || message.isBlank()
        ? "请求未完成（" + e.getClass().getSimpleName() + "），请检查生成服务是否启动。"
        : message;
  }

  public CompletableFuture<String> diagnose() {
    return background(() -> client.json("GET", "/v1/health/ready", null, null).toString());
  }

  public CompletableFuture<JsonNode> records(String id, long version) {
    return background(
        () ->
            client.json(
                "GET",
                "/v1/observations?definitionId=" + enc(id) + "&version=" + version,
                null,
                null));
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public void close() {
    closed = true;
    for (var future : callbacks)
      future.completeExceptionally(new IllegalStateException("Plugin stopped"));
    callbacks.clear();
    executor.shutdownNow();
  }
}
