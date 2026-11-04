/** 准备固定版本的本地测试服：核对下载散列，只安装本项目构建的插件。 */
import {
  readFile,
  writeFile,
  mkdir,
  copyFile,
  access,
  rename,
  unlink,
} from "node:fs/promises";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { resolve, join } from "node:path";
import { javaHome } from "./java.mjs";
const release = JSON.parse(
  await readFile(new URL("./paper-release.json", import.meta.url), "utf8"),
);
const dir = resolve("var/paper");
await mkdir(join(dir, "plugins"), { recursive: true });
const jar = join(dir, release.name);
const valid = async (file) =>
  createHash("sha256")
    .update(await readFile(file))
    .digest("hex") === release.sha256;
if (
  !(await access(jar).then(
    () => true,
    () => false,
  )) ||
  !(await valid(jar))
) {
  console.log(
    `Downloading Paper ${release.minecraftVersion}, build ${release.build}…`,
  );
  const response = await fetch(release.url, {
    headers: { "User-Agent": "generative-equipment-local-dev/0.1.0" },
    signal: AbortSignal.timeout(120000),
  });
  if (!response.ok)
    throw new Error(`Paper download failed: HTTP ${response.status}`);
  const data = Buffer.from(await response.arrayBuffer());
  if (createHash("sha256").update(data).digest("hex") !== release.sha256)
    throw new Error("Paper checksum mismatch");
  await writeFile(jar + ".part", data);
  await rename(jar + ".part", jar);
}
if (process.argv.includes("--download-only")) {
  console.log(`Verified: ${jar}`);
  process.exit(0);
}
const plugin = resolve(
  "java/paper-plugin/build/libs/GenerativeEquipment-0.1.0.jar",
);
if (
  !(await access(plugin).then(
    () => true,
    () => false,
  ))
)
  throw new Error("Build the plugin first: pnpm build:java");
await copyFile(plugin, join(dir, "plugins/GenerativeEquipment-0.1.0.jar"));
const eula = join(dir, "eula.txt");
if (
  !(await access(eula).then(
    () => true,
    () => false,
  ))
)
  await writeFile(
    eula,
    "# Review https://aka.ms/MinecraftEULA before setting true.\neula=false\n",
  );
if (!/^eula\s*=\s*true\s*$/m.test(await readFile(eula, "utf8"))) {
  console.error(`Review the Minecraft EULA and edit ${eula}, then rerun.`);
  process.exit(1);
}
const properties = join(dir, "server.properties");
// 仅初始化新测试服配置；保留用户以后修改的世界、端口和联机设置。
if (
  !(await access(properties).then(
    () => true,
    () => false,
  ))
)
  await writeFile(
    properties,
    "server-ip=127.0.0.1\nserver-port=25565\nonline-mode=true\nmotd=Generative Equipment S0\n",
  );
const home = await javaHome();
const child = spawn(
  home ? join(home, "bin/java") : "java",
  ["-Xms512M", "-Xmx2G", "-jar", jar, "--nogui"],
  { cwd: dir, stdio: "inherit", env: process.env },
);
child.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.on("exit", (code) => {
  process.exitCode = code ?? 1;
});
