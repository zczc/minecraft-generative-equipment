/** 校验本地设计快照。设计文档不随代码仓库提交，缺失时明确提示单独获取。 */
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
const reference = JSON.parse(await readFile("design-reference.json", "utf8"));
let failed = false;
for (const doc of reference.documents) {
  try {
    const content = await readFile(doc.path);
    const digest = createHash("sha256").update(content).digest("hex");
    if (digest !== doc.sha256) throw new Error("内容散列与锁定版本不一致");
    console.log(`${reference.designVersion}: ${doc.path} ✓`);
  } catch (e) {
    failed = true;
    console.error(`${doc.path}: ${e.message}`);
  }
}
if (failed) process.exitCode = 1;
