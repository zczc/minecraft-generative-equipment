import { createHash } from "node:crypto";
import canonicalize from "canonicalize";
import { assertJsonBoundary } from "./validation.js";
/** 使用 RFC 8785 规范化 JSON 生成幂等散列；键顺序不影响结果，内容变化影响结果。 */
export function hashJson(value: unknown): string {
  assertJsonBoundary(value);
  const text = (canonicalize as unknown as (v: unknown) => string | undefined)(
    value,
  );
  if (text === undefined) throw new Error("Cannot canonicalize input");
  return "sha256:" + createHash("sha256").update(text, "utf8").digest("hex");
}
