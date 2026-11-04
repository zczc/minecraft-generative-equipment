import { readFile } from "node:fs/promises";
import { join } from "node:path";
import {
  validate,
  hashJson,
  validateIntent,
  type WorldConfig,
  type GenerationPolicy,
  type MaterialCatalog,
  type GenerationRequest,
} from "@ge/protocol";
import { ApiFault, notImplemented } from "../errors.js";
/** 从独立世界配置解析材料语义；材料联想是创作提示，不授予新的宿主能力。 */
export class WorldCatalog {
  constructor(private root: string) {}
  /** 每次新任务读取当前配置；无效配置明确失败，不退回硬编码材料清单。 */
  async load(id: string) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(id))
      throw new ApiFault(422, "INVALID_WORLD", "Invalid world id");
    const read = async (name: string) =>
      JSON.parse(await readFile(join(this.root, id, name), "utf8")) as unknown;
    let world: WorldConfig,
      materials: MaterialCatalog,
      policy: GenerationPolicy;
    try {
      world = validate<WorldConfig>("WorldConfig", await read("world.json"));
      materials = validate<MaterialCatalog>(
        "MaterialCatalog",
        await read("materials.json"),
      );
      policy = validate<GenerationPolicy>(
        "GenerationPolicy",
        await read("generation-policy.json"),
      );
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "ENOENT")
        throw new ApiFault(
          404,
          "WORLD_NOT_FOUND",
          "World configuration not found",
        );
      throw new ApiFault(
        503,
        "WORLD_CONFIG_INVALID",
        "World configuration is invalid",
      );
    }
    if (
      world.id !== id ||
      new Set(materials.materials.map((m) => m.itemId)).size !==
        materials.materials.length
    )
      throw new ApiFault(
        503,
        "WORLD_CONFIG_INVALID",
        "Mismatched world id or duplicate material",
      );
    const snapshot = { world, materials, policy };
    return { ...snapshot, hash: hashJson(snapshot) };
  }
  /** 将物品编号展开为可传给模型的描述与数量，返回可持久化的完整快照。 */
  async resolveInput(request: GenerationRequest) {
    const snapshot = await this.load(request.worldRuleRef.id);
    if (snapshot.world.version !== request.worldRuleRef.version)
      throw new ApiFault(
        409,
        "WORLD_VERSION_MISMATCH",
        "World version has changed",
      );
    const intent = validateIntent(
      request.intentText,
      snapshot.policy.maxIntentBytes,
      snapshot.policy.intentLimit,
    );
    if (!intent.accepted)
      throw new ApiFault(
        422,
        intent.reason!,
        `最多 ${intent.limit} 个中文字符或英文单词，当前为 ${intent.count}。`,
        intent,
      );
    if (request.sourceEquipment.length)
      throw notImplemented("Existing-equipment generation");
    if (
      !request.materials.length ||
      request.materials.length > snapshot.policy.maxMaterialKinds
    )
      throw new ApiFault(
        422,
        "INVALID_MATERIALS",
        "Invalid number of material kinds",
      );
    if (
      new Set(request.materials.map((x) => x.definitionId)).size !==
      request.materials.length
    )
      throw new ApiFault(
        422,
        "DUPLICATE_MATERIAL",
        "Combine duplicate material rows before submission",
      );
    const materials = request.materials.map((input) => {
      const definition = snapshot.materials.materials.find(
        (m) => m.itemId === input.definitionId && m.enabled,
      );
      if (!definition)
        throw new ApiFault(
          422,
          "MATERIAL_NOT_ALLOWED",
          "A submitted material is disabled or unknown",
        );
      if (input.count > snapshot.policy.maxCountPerMaterial)
        throw new ApiFault(
          422,
          "MATERIAL_LIMIT_EXCEEDED",
          "Material quantity exceeds world policy",
        );
      return { ...definition, count: input.count };
    });
    return {
      request,
      world: snapshot.world,
      policy: snapshot.policy,
      materials,
      materialCatalogVersion: snapshot.materials.version,
      worldConfigHash: snapshot.hash,
    };
  }
}
export type ResolvedGenerationInput = Awaited<
  ReturnType<WorldCatalog["resolveInput"]>
>;
