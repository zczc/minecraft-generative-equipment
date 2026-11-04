package dev.generative.equipment.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** 与 TypeScript 一致的意图计数；使用可见字符分段，避免拆开组合表情。 */
public final class IntentPolicy {
  private static final Pattern GRAPHEME = Pattern.compile("\\X");
  private static final Pattern LATIN = Pattern.compile("[A-Za-z]\\p{M}*");
  private static final Pattern IGNORE = Pattern.compile("[\\p{IsWhite_Space}\\p{P}]+");

  private IntentPolicy() {}

  public record Result(boolean accepted, int count, int limit, String reason) {}

  public static Result validate(String text) {
    return validate(text, 256, 10);
  }

  /** 字节上限独立检查，英文单词长短不会绕过传输预算。 */
  public static Result validate(String text, int maxBytes, int limit) {
    Objects.requireNonNull(text);
    var parts = new ArrayList<String>();
    var matcher = GRAPHEME.matcher(text);
    while (matcher.find()) parts.add(matcher.group());
    int count = 0;
    String mode = "";
    for (int i = 0; i < parts.size(); i++) {
      String p = parts.get(i);
      if (LATIN.matcher(p).matches()) {
        if (!mode.equals("word")) count++;
        mode = "word";
      } else if ((p.equals("'") || p.equals("’"))
          && mode.equals("word")
          && i + 1 < parts.size()
          && LATIN.matcher(parts.get(i + 1)).matches()) {
      } else if (p.matches("[0-9]")) {
        if (!mode.equals("number")) count++;
        mode = "number";
      } else {
        mode = "";
        if (!IGNORE.matcher(p).matches()) count++;
      }
    }
    String reason =
        text.getBytes(StandardCharsets.UTF_8).length > maxBytes
            ? "INPUT_BYTES_EXCEEDED"
            : count > limit ? "INTENT_TOO_LONG" : null;
    return new Result(reason == null, count, limit, reason);
  }
}
