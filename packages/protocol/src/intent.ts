export type IntentValidation = {
  accepted: boolean;
  count: number;
  limit: number;
  reason: "INTENT_TOO_LONG" | "INPUT_BYTES_EXCEEDED" | null;
};
const segmenter = new Intl.Segmenter("und", { granularity: "grapheme" });
const latin = (s: string) => /^[A-Za-z]\p{M}*$/u.test(s);
/**
 * 按玩家看到的字符计数：中文逐字、英文连续成词，组合表情不拆开。
 * 字节上限独立于字数限制，避免一个超长英文词或大量标点绕过输入预算。
 * 与 Java IntentPolicy 使用同一组跨语言 fixture，修改规则时两端一起验证。
 */
export function validateIntent(
  text: string,
  maxBytes = 256,
  limit = 10,
): IntentValidation {
  const parts = Array.from(segmenter.segment(text), (x) => x.segment);
  let count = 0,
    mode: "" | "word" | "number" = "";
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i]!;
    if (latin(p)) {
      if (mode !== "word") count++;
      mode = "word";
    } else if (
      (p === "'" || p === "’") &&
      mode === "word" &&
      latin(parts[i + 1] ?? "")
    ) {
    } else if (/^[0-9]$/.test(p)) {
      if (mode !== "number") count++;
      mode = "number";
    } else {
      mode = "";
      if (!/^[\p{White_Space}\p{Punctuation}]+$/u.test(p)) count++;
    }
  }
  const reason =
    Buffer.byteLength(text, "utf8") > maxBytes
      ? "INPUT_BYTES_EXCEEDED"
      : count > limit
        ? "INTENT_TOO_LONG"
        : null;
  return { accepted: reason === null, count, limit, reason };
}
