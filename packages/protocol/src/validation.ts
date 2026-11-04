import { Ajv2020 } from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { schemas } from "./generated/schemas.js";
// TypeScript 和 Java 共用同一批 schema；这里只缓存编译结果，不修改调用者数据。
const ajv = new Ajv2020({
  allErrors: true,
  strict: false,
  validateFormats: true,
});
(addFormats as unknown as (a: Ajv2020) => void)(ajv);
const compiled = new Map<string, ReturnType<Ajv2020["compile"]>>();
export class ContractError extends Error {
  constructor(public readonly details: { path: string; message: string }[]) {
    super("Invalid contract payload");
  }
}
/** schema 之外的通用边界：拒绝非 JSON 值、过深嵌套与危险原型键。 */
export function assertJsonBoundary(value: unknown, depth = 0): void {
  if (depth > 32)
    throw new ContractError([
      { path: "/", message: "JSON nesting exceeds 32" },
    ]);
  if (value === null || typeof value === "string" || typeof value === "boolean")
    return;
  if (typeof value === "number" && Number.isFinite(value)) return;
  if (Array.isArray(value)) {
    for (const x of value) assertJsonBoundary(x, depth + 1);
    return;
  }
  if (
    typeof value === "object" &&
    value !== null &&
    [Object.prototype, null].includes(Object.getPrototypeOf(value))
  ) {
    for (const [key, x] of Object.entries(value)) {
      if (["__proto__", "prototype", "constructor"].includes(key))
        throw new ContractError([
          { path: key, message: "Reserved object key" },
        ]);
      assertJsonBoundary(x, depth + 1);
    }
    return;
  }
  throw new ContractError([
    { path: "/", message: "Only finite JSON values are allowed" },
  ]);
}
/** 校验成功后返回原值；失败提供字段路径，不把未知字段静默删除。 */
export function validate<T>(name: string, value: unknown): T {
  assertJsonBoundary(value);
  const schema = schemas[name];
  if (!schema) throw new Error(`Unknown schema ${name}`);
  let check = compiled.get(name);
  if (!check) {
    check = ajv.compile(schema);
    compiled.set(name, check);
  }
  if (!check(value))
    throw new ContractError(
      (check.errors ?? []).map((e) => ({
        path: e.instancePath || "/",
        message:
          (e.message ?? "Invalid value") +
          (e.params.additionalProperty
            ? ` (${e.params.additionalProperty})`
            : "") +
          (e.params.allowedValues
            ? `; allowed=${JSON.stringify(e.params.allowedValues)}`
            : ""),
      })),
    );
  return value as T;
}
