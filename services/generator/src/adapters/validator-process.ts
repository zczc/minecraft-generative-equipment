import { spawn } from "node:child_process";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parse } from "acorn";
import {
  ContractError,
  hashJson,
  validate,
  type GeneratedSource,
  type CapabilityProfile,
} from "@ge/protocol";
import type { CandidateValidator, ValidationReport } from "../ports/index.js";
import type { GenerationConfig } from "../config.js";
import { randomUUID } from "node:crypto";
export const ENGINE = "quickjs-2026-06-04-ge1";
/** 语法检查只是提前反馈错误；实际隔离由无系统绑定的 QuickJS 和独立验证进程承担。 */
export function checkSource(source: GeneratedSource): void {
  validate("GeneratedSource", source);
  const ast = parse(source.behaviorSource, {
    ecmaVersion: 2024,
    sourceType: "module",
  });
  const exports = new Set<string>();
  const walk = (node: any) => {
    if (!node || typeof node !== "object") return;
    if (
      node.async ||
      node.type === "AwaitExpression" ||
      node.type === "ImportExpression" ||
      node.regex
    )
      throw new Error(
        "Async, dynamic import and regular expressions are unavailable",
      );
    if (node.type === "ImportDeclaration" && node.source.value !== "@ge/sdk")
      throw new Error("Only @ge/sdk may be imported");
    if (
      node.type === "ExportDefaultDeclaration" ||
      node.type === "ExportAllDeclaration"
    )
      throw new Error("Use named function exports");
    if (node.type === "ExportNamedDeclaration") {
      if (
        node.declaration?.type !== "FunctionDeclaration" ||
        !["onEvent", "render"].includes(node.declaration.id?.name)
      )
        throw new Error("Only onEvent/render function exports allowed");
      exports.add(node.declaration.id.name);
    }
    for (const value of Object.values(node))
      if (Array.isArray(value)) value.forEach(walk);
      else if (value && typeof value === "object") walk(value);
  };
  walk(ast);
  if (!exports.has("onEvent")) throw new Error("Missing onEvent export");
}
export class ValidatorProcess implements CandidateValidator {
  constructor(private config: GenerationConfig) {}
  /** 启动时真实加载 JNI 并执行一次程序，避免 JAR 存在但原生运行器不可用时误报就绪。 */
  async available(): Promise<boolean> {
    return new Promise((resolve) => {
      const child = spawn(
        this.config.javaCommand,
        [
          "--enable-native-access=ALL-UNNAMED",
          "-Xmx256m",
          "-jar",
          this.config.validatorJar,
          "--probe",
        ],
        {
          env: {
            PATH: process.env.PATH ?? "/usr/bin:/bin",
            LANG: "en_US.UTF-8",
          },
          stdio: ["ignore", "pipe", "ignore"],
        },
      );
      let output = "";
      const timer = setTimeout(() => child.kill("SIGKILL"), 10000);
      child.stdout.on("data", (chunk: Buffer) => {
        output += chunk.toString("utf8");
        if (output.length > 4096) child.kill("SIGKILL");
      });
      child.on("error", () => {
        clearTimeout(timer);
        resolve(false);
      });
      child.on("close", (code) => {
        clearTimeout(timer);
        try {
          const report = JSON.parse(output);
          resolve(
            code === 0 &&
              report.ready === true &&
              report.engineBuildId === ENGINE,
          );
        } catch {
          resolve(false);
        }
      });
    });
  }
  async validate(
    source: GeneratedSource,
    signal: AbortSignal,
    profile: CapabilityProfile,
  ): Promise<ValidationReport> {
    const base = {
      sourceHash: hashJson(source),
      engineBuildId: ENGINE,
      capabilityProfileId: profile.profileId,
    };
    try {
      checkSource(source);
    } catch (e) {
      return {
        ...base,
        passed: false,
        checks: [{ name: "source-syntax", status: "FAIL" }],
        diagnostics:
          e instanceof ContractError
            ? e.details.slice(0, 12).map((d) => `${d.path}: ${d.message}`)
            : [String((e as Error).message)],
      };
    }
    const dir = await mkdtemp(join(tmpdir(), "ge-validator-"));
    try {
      const file = join(dir, "request.json");
      const validationId = randomUUID();
      await writeFile(
        file,
        JSON.stringify({ validationId, ...base, profile, source }),
        { mode: 0o600 },
      );
      const args = [
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx256m",
        "-jar",
        this.config.validatorJar,
        "--request",
        file,
      ];
      const command = this.config.javaCommand;
      const report = await new Promise<ValidationReport>((resolve, reject) => {
        // 凭据不传给验证进程；模型文本只能经文件进入，不插进 shell 命令。
        const child = spawn(command, args, {
          cwd: dir,
          env: {
            PATH: process.env.PATH ?? "/usr/bin:/bin",
            LANG: "en_US.UTF-8",
            TMPDIR: dir,
          },
          stdio: ["ignore", "pipe", "pipe"],
        });
        let output = "";
        let size = 0;
        let ended = false;
        const stop = () => {
          child.kill("SIGKILL");
        };
        const timer = setTimeout(stop, 20000);
        signal.addEventListener("abort", stop, { once: true });
        if (signal.aborted) stop();
        child.stdout.on("data", (chunk: Buffer) => {
          size += chunk.length;
          if (size > 1048576) stop();
          else output += chunk.toString("utf8");
        });
        child.stderr.on("data", (chunk: Buffer) => {
          size += chunk.length;
          if (size > 1048576) stop();
        });
        const finish = () => {
          ended = true;
          clearTimeout(timer);
          signal.removeEventListener("abort", stop);
        };
        child.on("error", () => {
          finish();
          reject(new Error("Validator process could not start"));
        });
        child.on("exit", (code) => {
          if (ended) return;
          finish();
          try {
            const parsed = JSON.parse(output);
            if (
              parsed.validationId !== validationId ||
              parsed.sourceHash !== base.sourceHash ||
              parsed.engineBuildId !== ENGINE ||
              parsed.capabilityProfileId !== profile.profileId ||
              signal.aborted
            )
              throw new Error("Invalid validator report");
            const names = new Set(
              (parsed.checks ?? [])
                .filter((c: any) => c.status === "PASS")
                .map((c: any) => c.name),
            );
            parsed.passed =
              code === 0 &&
              parsed.passed === true &&
              [
                "source-contract",
                "state-schema",
                "quickjs-compilation",
                "declared-scenarios",
                "rejected-world-operations",
                "presentation-contract",
              ].every((n) => names.has(n)) &&
              parsed.checks.every((c: any) => c.status === "PASS");
            resolve(parsed);
          } catch {
            reject(
              new Error("Validator did not return a complete matching report"),
            );
          }
        });
      });
      return report;
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  }
}
