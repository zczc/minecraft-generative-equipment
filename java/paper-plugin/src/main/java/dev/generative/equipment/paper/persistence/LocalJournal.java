package dev.generative.equipment.paper.persistence;

import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.core.PackageLoader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 本地交付证据：后台写临时文件、刷盘、原子替换。未完成 PREPARED 不因重启而自动重发。 */
public final class LocalJournal {
  private final Path root;

  public LocalJournal(Path root) throws java.io.IOException {
    this.root = root;
    Files.createDirectories(root);
  }

  private Path file(String key) {
    return root.resolve(PackageLoader.sha(key.getBytes(StandardCharsets.UTF_8)) + ".json");
  }

  public JsonNode get(String key) throws java.io.IOException {
    var path = file(key);
    return Files.exists(path) ? MAPPER.readTree(Files.readAllBytes(path)) : null;
  }

  public void put(String key, JsonNode value) throws java.io.IOException {
    Path target = file(key), temp = Files.createTempFile(root, "journal-", ".tmp");
    try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(MAPPER.writeValueAsBytes(value));
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  public List<JsonNode> entries() throws java.io.IOException {
    List<JsonNode> result = new ArrayList<>();
    try (var paths = Files.list(root)) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList())
        result.add(MAPPER.readTree(Files.readAllBytes(path)));
    }
    return result;
  }
}
