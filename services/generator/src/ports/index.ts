import type { GeneratedSource, CapabilityProfile } from "@ge/protocol";
import type { ResolvedGenerationInput } from "../adapters/world-catalog.js";
export type GenerationInput = ResolvedGenerationInput & {
  capabilityProfile: CapabilityProfile;
};
export type ModelResult = {
  source: GeneratedSource | null;
  rawText?: string;
  format?: string;
  diagnostics?: string[];
  usage: Record<string, number> | null;
  model?: string;
  origin?: "model" | "fixture";
};
export type ValidationReport = {
  passed: boolean;
  diagnostics: string[];
  sourceHash: string;
  engineBuildId: string;
  capabilityProfileId: string;
  checks: { name: string; status: string }[];
  metrics?: Record<string, unknown>;
};
/** 冻结输入和受限诊断送给模型；修复也是有预算的真实调用，不能伪造来源。 */
export interface ModelProvider {
  generate(
    input: GenerationInput,
    signal: AbortSignal,
    repair?: {
      source?: GeneratedSource;
      rawText?: string;
      diagnostics: string[];
    },
  ): Promise<ModelResult>;
}
/** 与正式游戏复用同一 QuickJS 运行器，在独立进程执行候选与场景。 */
export interface CandidateValidator {
  validate(
    source: GeneratedSource,
    signal: AbortSignal,
    profile: CapabilityProfile,
  ): Promise<ValidationReport>;
}
