import { spawn } from "node:child_process";
import { readdir, access, readFile } from "node:fs/promises";
import { resolve } from "node:path";
export async function javaHome() {
  if (process.env.GE_JAVA_HOME) return resolve(process.env.GE_JAVA_HOME);
  if (process.env.JAVA_HOME) return process.env.JAVA_HOME;
  // 本机验证可选择独立工具链；路径保存在 Git 忽略区，不污染仓库或系统 Java。
  const selected = await readFile(
    "var/toolchains/active-java-home.txt",
    "utf8",
  ).catch(() => null);
  if (
    selected &&
    (await access(`${selected.trim()}/bin/java`).then(
      () => true,
      () => false,
    ))
  )
    return selected.trim();
  const entries = await readdir("var/toolchains").catch(() => []);
  for (const entry of entries
    .filter((x) => x.startsWith("jdk-"))
    .sort()
    .reverse())
    for (const suffix of ["Contents/Home", ""]) {
      const home = resolve("var/toolchains", entry, suffix);
      if (
        await access(`${home}/bin/java`).then(
          () => true,
          () => false,
        )
      )
        return home;
    }
  return undefined;
}
if (
  process.argv[1] &&
  resolve(process.argv[1]) === resolve("scripts/java.mjs")
) {
  const home = await javaHome();
  const command = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
  const child = spawn(command, process.argv.slice(2).concat("--no-daemon"), {
    cwd: "java",
    stdio: "inherit",
    env: { ...process.env, ...(home ? { JAVA_HOME: home } : {}) },
  });
  child.on("error", (e) => {
    console.error("Gradle could not start:", e.message);
    process.exitCode = 1;
  });
  child.on("exit", (code) => {
    process.exitCode = code ?? 1;
  });
}
