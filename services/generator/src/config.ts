import { resolve } from "node:path";
export type Config = {
  hostId: string;
  token: string;
  port: number;
  dataDir: string;
  worldsDir: string;
  logger: boolean;
  generation?: GenerationConfig;
};
/** 只读取环境配置，不输出密钥。内部令牌缺失时启动失败，避免无鉴权运行。 */
export function loadConfig(env = process.env): Config {
  const token = env.GE_INTERNAL_TOKEN ?? "";
  if (token.length < 32)
    throw new Error("GE_INTERNAL_TOKEN must contain at least 32 characters");
  const hostId = env.GE_HOST_ID ?? "local-paper";
  if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(hostId))
    throw new Error("Invalid GE_HOST_ID");
  const port = Number(env.GE_PORT ?? 8765);
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error("Invalid GE_PORT");
  return {
    hostId,
    token,
    port,
    dataDir: resolve(env.GE_DATA_DIR ?? "var/generator"),
    worldsDir: resolve(env.GE_WORLDS_DIR ?? "worlds"),
    logger: true,
    generation: generationConfig(env),
  };
}

export type GenerationConfig = {
  apiKey: string;
  baseUrl: string;
  model: string;
  maxTokens: number;
  enableThinking: boolean;
  reasoningEffort?: "low" | "high" | "max";
  timeoutMs: number;
  javaCommand: string;
  validatorJar: string;
};
/** 模型配置独立于世界配置；缺少密钥时服务仍可查询已发布装备，但不能新生成。 */
export function generationConfig(env = process.env): GenerationConfig {
  const integer = (key: string, fallback: number, min: number, max: number) => {
    const value = Number(env[key] ?? fallback);
    if (!Number.isInteger(value) || value < min || value > max)
      throw new Error(`Invalid ${key}`);
    return value;
  };
  const baseUrl =
    env.GE_ALIYUN_BASE_URL ??
    "https://dashscope.aliyuncs.com/compatible-mode/v1";
  const url = new URL(baseUrl);
  if (
    url.protocol !== "https:" &&
    !(
      url.protocol === "http:" &&
      ["127.0.0.1", "localhost"].includes(url.hostname)
    )
  )
    throw new Error("Model endpoint must use HTTPS");
  const thinking = env.GE_ALIYUN_ENABLE_THINKING ?? "true";
  if (!["true", "false"].includes(thinking))
    throw new Error("Invalid GE_ALIYUN_ENABLE_THINKING");
  const effort = env.GE_ALIYUN_REASONING_EFFORT ?? "low";
  if (!["", "low", "high", "max"].includes(effort))
    throw new Error("Invalid GE_ALIYUN_REASONING_EFFORT");
  return {
    apiKey: env.GE_ALIYUN_API_KEY ?? "",
    baseUrl: baseUrl.replace(/\/$/, ""),
    model: env.GE_ALIYUN_MODEL ?? "deepseek-v4.1-flash-1017",
    enableThinking: thinking === "true",
    ...(effort ? { reasoningEffort: effort as "low" | "high" | "max" } : {}),
    maxTokens: integer("GE_ALIYUN_MAX_TOKENS", 16384, 512, 393216),
    timeoutMs: integer("GE_ALIYUN_TIMEOUT_MS", 180000, 1000, 1800000),
    javaCommand: env.GE_JAVA_COMMAND ?? "java",
    validatorJar: resolve(
      env.GE_VALIDATOR_JAR ?? "java/validator-cli/build/libs/validator-cli.jar",
    ),
  };
}
