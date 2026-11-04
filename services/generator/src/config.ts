import { resolve } from "node:path";
export type Config = {
  hostId: string;
  token: string;
  port: number;
  dataDir: string;
  worldsDir: string;
  logger: boolean;
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
  };
}
