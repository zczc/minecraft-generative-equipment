package dev.generative.equipment.runtime;

import java.io.IOException;
import java.nio.file.*;

/** 原生库从可信 JAR 资源提取；从不加载模型提供的库或字节码。 */
public final class NativeBridge {
  static {
    String os = System.getProperty("os.name").toLowerCase();
    String arch =
        System.getProperty("os.arch").equals("amd64")
            ? "x64"
            : System.getProperty("os.arch").equals("aarch64")
                ? "arm64"
                : System.getProperty("os.arch");
    String target = os.contains("mac") ? "darwin-universal" : "linux-" + arch;
    String file = os.contains("mac") ? "libgequickjs.dylib" : "libgequickjs.so";
    try (var in = NativeBridge.class.getResourceAsStream("/native/" + target + "/" + file)) {
      if (in == null) throw new IOException("Native runtime not built for " + target);
      Path folder = Files.createTempDirectory("ge-quickjs-");
      folder.toFile().deleteOnExit();
      Path lib = folder.resolve(file);
      Files.copy(in, lib);
      lib.toFile().deleteOnExit();
      System.load(lib.toAbsolutePath().toString());
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private NativeBridge() {}

  public interface Queries {
    byte[] query(byte[] name, byte[] args);
  }

  public static native byte[] compile(byte[] source);

  public static native byte[] execute(
      byte[] bytecode,
      byte[] input,
      byte[] event,
      boolean render,
      Queries queries,
      long memoryBytes,
      long durationNanos,
      int maxQueries);
}
