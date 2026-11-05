package dev.generative.equipment.core;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.protocol.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** 后台加载内部服务发布的包。先检查完整 ZIP 和每个文件，再由本机引擎编译源码；不接收字节码。 */
public final class PackageLoader {
  private static final Set<String> FILES =
      Set.of(
          "manifest.json",
          "equipment.json",
          "behavior.js",
          "definition.json",
          "parameters.json",
          "state-schema.json",
          "presentation.json",
          "scenarios.json",
          "provenance.json",
          "validation-report.json");
  private final HostPorts.RuleRuntime runtime;
  private final JsonNode profile;

  public PackageLoader(HostPorts.RuleRuntime runtime, JsonNode profile) {
    this.runtime = runtime;
    this.profile = profile;
  }

  public static String sha(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public LoadedDefinition prepare(byte[] zip, JsonNode view) throws Exception {
    if (zip.length > 1048576
        || zip.length != view.path("bytes").asInt()
        || !("sha256:" + sha(zip)).equals(view.path("bundleHash").asText()))
      throw new IllegalArgumentException("Bundle checksum or size mismatch");
    Map<String, byte[]> files = new HashMap<>();
    int total = 0;
    try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (!FILES.contains(entry.getName())
            || entry.isDirectory()
            || files.containsKey(entry.getName()))
          throw new IllegalArgumentException("Unexpected or repeated bundle path");
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
          total += n;
          if (total > 2097152) throw new IllegalArgumentException("Expanded bundle limit");
          out.write(buffer, 0, n);
        }
        files.put(entry.getName(), out.toByteArray());
      }
    }
    if (!files.keySet().equals(FILES)) throw new IllegalArgumentException("Incomplete bundle");
    var manifest = MAPPER.readTree(files.get("manifest.json"));
    JsonContracts.validate("PackageManifest", manifest);
    if (!manifest.equals(view.path("manifest"))
        || !manifest.path("definitionRef").equals(view.path("definitionRef")))
      throw new IllegalArgumentException("Manifest changed");
    if (!manifest.path("engineBuildId").equals(profile.path("engineBuildId"))
        || !HostCapabilities.acceptsProfile(profile, manifest.path("capabilityProfileId").asText()))
      throw new IllegalArgumentException("Host capability or engine mismatch");
    Set<String> declared = new HashSet<>();
    for (var f : manifest.path("files")) {
      String name = f.path("path").asText();
      byte[] data = files.get(name);
      if (name.equals("manifest.json")
          || data == null
          || !declared.add(name)
          || data.length != f.path("bytes").asInt()
          || !sha(data).equals(f.path("sha256").asText()))
        throw new IllegalArgumentException("Bundle file mismatch");
    }
    if (declared.size() != FILES.size() - 1)
      throw new IllegalArgumentException("Incomplete manifest file list");
    var report = MAPPER.readTree(files.get("validation-report.json"));
    if (!report.path("passed").asBoolean()
        || !report.path("sourceHash").equals(manifest.path("sourceHash"))
        || !report.path("engineBuildId").equals(manifest.path("engineBuildId"))
        || !report.path("capabilityProfileId").equals(manifest.path("capabilityProfileId")))
      throw new IllegalArgumentException("Validation evidence mismatch");
    Set<String> passed = new HashSet<>();
    for (var check : report.path("checks")) {
      if (!check.path("status").asText().equals("PASS"))
        throw new IllegalArgumentException("Incomplete validation");
      passed.add(check.path("name").asText());
    }
    if (!passed.containsAll(
        Set.of(
            "source-contract",
            "state-schema",
            "quickjs-compilation",
            "declared-scenarios",
            "rejected-world-operations",
            "presentation-contract")))
      throw new IllegalArgumentException("Missing required validation checks");
    var equipment = MAPPER.readTree(files.get("equipment.json"));
    JsonContracts.validate("EquipmentSource", equipment);
    HostCapabilities.equipment(equipment, profile);
    if (!equipment.path("parameters").equals(MAPPER.readTree(files.get("parameters.json")))
        || !equipment
            .path("initialState")
            .equals(MAPPER.readTree(files.get("state-schema.json")).path("initialState")))
      throw new IllegalArgumentException("Derived source mismatch");
    var ref = MAPPER.treeToValue(manifest.path("definitionRef"), Contracts.DefinitionRef.class);
    var prepared =
        runtime.prepare(
            new VerifiedPackage(
                ref,
                manifest.path("sourceHash").asText(),
                manifest.path("engineBuildId").asText(),
                manifest,
                new String(files.get("behavior.js"), StandardCharsets.UTF_8)));
    return new LoadedDefinition(
        prepared,
        equipment,
        new StateSchema(equipment.path("stateSchema")),
        MAPPER.readTree(files.get("provenance.json")));
  }
}
