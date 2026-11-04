import { mkdir, readFile, writeFile, access, copyFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { resolve } from "node:path";
import { javaHome } from "./java.mjs";
// 固定源码散列；模型与装备包不能替换运行引擎。原生库随插件发布，玩家不需要安装它。
const version = "2026-06-04";
const sha = "b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a";
const dir = resolve("var/native");
await mkdir(dir, { recursive: true });
const archive = `${dir}/quickjs.tar.xz`;
let bytes = await readFile(archive).catch(() => null);
if (!bytes || createHash("sha256").update(bytes).digest("hex") !== sha) {
  const r = await fetch(
    `https://bellard.org/quickjs/quickjs-${version}.tar.xz`,
    { signal: AbortSignal.timeout(120000) },
  );
  if (!r.ok) throw new Error(`QuickJS download: ${r.status}`);
  bytes = Buffer.from(await r.arrayBuffer());
  if (createHash("sha256").update(bytes).digest("hex") !== sha)
    throw new Error("QuickJS checksum mismatch");
  await writeFile(archive, bytes);
}
function run(cmd, args) {
  const p = spawnSync(cmd, args, { stdio: "inherit" });
  if (p.status !== 0) throw new Error(`${cmd} failed`);
}
const src = `${dir}/quickjs-${version}`;
if (
  !(await access(`${src}/quickjs.h`).then(
    () => true,
    () => false,
  ))
)
  run("tar", ["-xf", archive, "-C", dir]);
const home = await javaHome();
if (!home) throw new Error("Set JAVA_HOME or GE_JAVA_HOME");
const mac = process.platform === "darwin";
if (!mac && process.platform !== "linux")
  throw new Error("Native build currently supports macOS and Linux");
const target = mac ? "darwin-universal" : `linux-${process.arch}`;
const out = resolve(
  `java/runtime-quickjs/build/generated/native/native/${target}`,
);
await mkdir(out, { recursive: true });
const lib = `${out}/${mac ? "libgequickjs.dylib" : "libgequickjs.so"}`;
const cc = process.env.CC || "cc";
const flags = [
  "-O2",
  "-fPIC",
  ...(mac ? ["-arch", "x86_64", "-arch", "arm64"] : ["-D_GNU_SOURCE"]),
  "-fno-strict-aliasing",
  `-DCONFIG_VERSION="${version}"`,
  "-I" + src,
];
// QuickJS 源码固定，分别缓存目标文件；修改 JNI 桥时不必重复编译整个引擎。
const objDir = `${dir}/objects-${version}-${target}-o2`;
await mkdir(objDir, { recursive: true });
const objects = [];
for (const name of ["quickjs", "libregexp", "libunicode", "cutils", "dtoa"]) {
  const obj = `${objDir}/${name}.o`;
  if (
    !(await access(obj).then(
      () => true,
      () => false,
    ))
  )
    run(cc, [...flags, "-c", `${src}/${name}.c`, "-o", obj]);
  objects.push(obj);
}
run(cc, [
  ...flags,
  ...(mac ? ["-dynamiclib"] : ["-shared"]),
  "-I" + home + "/include",
  "-I" + home + "/include/" + (mac ? "darwin" : "linux"),
  "java/runtime-quickjs/src/main/c/ge_quickjs.c",
  ...objects,
  "-lm",
  "-lpthread",
  "-o",
  lib,
]);
await copyFile(`${src}/LICENSE`, `${out}/QUICKJS-LICENSE`);
console.log(`QuickJS ${version}: ${target}`);
