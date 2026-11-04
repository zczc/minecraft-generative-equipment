import type { GeneratedSource } from "@ge/protocol";
import type { ResolvedGenerationInput } from "../adapters/world-catalog.js";
/** 模型适配器：输入是冻结快照，输出是源文件和真实用量；失败不伪造候选。 */
export interface ModelProvider {
  generate(
    input: ResolvedGenerationInput,
    signal: AbortSignal,
  ): Promise<{ source: GeneratedSource; usage: Record<string, number> | null }>;
}
/** 隔离验证器：尚未执行的检查不能当作通过。 */
export interface CandidateValidator {
  validate(
    source: GeneratedSource,
    signal: AbortSignal,
  ): Promise<{ passed: boolean; diagnostics: string[] }>;
}
/** 只整理候选文件；打包成功不代表代码验证通过。 */
export interface PackageBuilder {
  build(
    source: GeneratedSource,
  ): Promise<{ candidatePath: string; sourceHash: string }>;
}
/** S0 禁止发布；后续接口只能接收带可信验证报告的不可变产物。 */
export interface DefinitionPublisher {
  publish(): Promise<never>;
}
