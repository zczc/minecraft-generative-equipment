import { type GeneratedSource } from "@ge/protocol";
import type { GenerationConfig } from "../../config.js";
import type {
  GenerationInput,
  ModelProvider,
  ModelResult,
} from "../../ports/index.js";
import { ApiFault } from "../../errors.js";
import { parseCompactOutput } from "./compact-output.js";
import { compactPrompt } from "./compact-prompt.js";

/** 调用 OpenAI-compatible Chat Completions；保留原始源码和用量，解析失败也可带诊断修复。 */
export class AliyunModelProvider implements ModelProvider {
  constructor(private config: GenerationConfig) {}
  async generate(
    input: GenerationInput,
    signal: AbortSignal,
    repair?: {
      source?: GeneratedSource;
      rawText?: string;
      diagnostics: string[];
    },
  ): Promise<ModelResult> {
    if (!this.config.apiKey)
      throw new ApiFault(503, "MODEL_NOT_CONFIGURED", "阿里云密钥尚未配置");
    const messages = [
      { role: "system", content: compactPrompt },
      {
        role: "user",
        content: JSON.stringify({
          world: input.world,
          materials: input.materials,
          intentText: input.request.intentText,
          environment: input.request.generationContext,
          policy: input.policy,
          capabilityProfile: input.capabilityProfile,
        }),
      },
    ];
    if (repair) {
      if (repair.rawText !== undefined)
        messages.push({ role: "assistant", content: repair.rawText });
      messages.push({
        role: "user",
        content:
          "修改上一个程序，修复诊断并返回简短 YAML 声明＋原样代码。不要仅解释或原样重发；保留能力意图。诊断：" +
          JSON.stringify(repair.diagnostics),
      });
    }
    const start = Date.now();
    const response = await fetch(`${this.config.baseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${this.config.apiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: this.config.model,
        messages,
        max_tokens: this.config.maxTokens,
        enable_thinking: this.config.enableThinking,
        ...(this.config.enableThinking && this.config.reasoningEffort
          ? { reasoning_effort: this.config.reasoningEffort }
          : {}),
      }),
      signal: AbortSignal.any([
        signal,
        AbortSignal.timeout(this.config.timeoutMs),
      ]),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new ApiFault(
        503,
        "MODEL_HTTP_ERROR",
        `模型接口返回 HTTP ${response.status}`,
      );
    }
    const reader = response.body!.getReader();
    let size = 0;
    const chunks: Uint8Array[] = [];
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 1048576) {
        await reader.cancel();
        throw new ApiFault(422, "MODEL_OUTPUT_TOO_LARGE", "模型响应过大");
      }
      chunks.push(value);
    }
    const result = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    // 只记录用量，不持久化模型思考文本；失败和截断也是真实付费调用。
    const usage: Record<string, number> = { elapsed_ms: Date.now() - start };
    for (const key of ["prompt_tokens", "completion_tokens", "total_tokens"])
      if (Number.isFinite(result.usage?.[key])) usage[key] = result.usage[key];
    for (const [key, value] of Object.entries({
      cached_tokens: result.usage?.prompt_tokens_details?.cached_tokens,
      reasoning_tokens:
        result.usage?.completion_tokens_details?.reasoning_tokens,
    }))
      if (typeof value === "number" && Number.isFinite(value))
        usage[key] = value;
    const rawText = result.choices?.[0]?.message?.content ?? "";
    usage.answer_chars = rawText.length;
    if (
      usage.completion_tokens !== undefined &&
      usage.reasoning_tokens !== undefined
    )
      usage.answer_tokens = usage.completion_tokens - usage.reasoning_tokens;
    const base = {
      rawText,
      usage,
      format: "compact-v1",
      model: result.model ?? this.config.model,
    };
    if (result.choices?.[0]?.finish_reason !== "stop")
      return {
        ...base,
        source: null,
        diagnostics: [
          "Output incomplete; reduce program size and return the complete module within the token budget",
        ],
      };
    try {
      return {
        ...base,
        source: await parseCompactOutput(rawText, input.capabilityProfile),
      };
    } catch (e) {
      return {
        ...base,
        source: null,
        diagnostics: [String((e as Error).message).slice(0, 512)],
      };
    }
  }
}
