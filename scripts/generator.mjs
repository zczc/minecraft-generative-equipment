import { spawn } from "node:child_process";
import { join } from "node:path";
import { javaHome } from "./java.mjs";
const home = await javaHome();
// 与 Gradle 使用同一 Java，避免本机没有全局 java 时验证服务误用系统占位命令。
const child = spawn(process.execPath, ["services/generator/dist/main.js"], {
  stdio: "inherit",
  env: {
    ...process.env,
    ...(home ? { GE_JAVA_COMMAND: join(home, "bin/java") } : {}),
  },
});
for (const signal of ["SIGTERM", "SIGINT"])
  process.on(signal, () => child.kill(signal));
child.on("exit", (code) => {
  process.exitCode = code ?? 1;
});
