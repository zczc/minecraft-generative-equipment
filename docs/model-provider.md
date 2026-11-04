# 模型接入、精简格式与实测

## 输出职责

主生成使用 `compact-v1`：几行 YAML 声明加原样 JavaScript，不要求模型输出 JSON，也不把代码嵌入字符串。正文目标 1,000—2,000 token。格式缩短与职责拆分同时进行，不靠改扩展名或提高输出上限节省 token。

```text
---
name: 装备名
description: 操作、限制和恢复方式
base: feather
state:
  charge: [0, 0, 100]
params:
  radius: 6
cues:
  charging: [ongoing, 12]
  release: [result, 8]
---
function act(g,e) { /* 这件装备独有的逻辑 */ }
function draw(v) { /* 这件装备独有的图形公式 */ }
```

这是格式示意，不是可发布装备。完整手写样例位于 `tests/fixtures/equipment/pulse.compact.txt`，明确用于测试，不算模型成果。

| 模型编写 | 框架补齐 |
|---|---|
| 状态初值、范围或枚举 | 有界 schema、初始状态和完整提交 |
| 自由参数、条件、公式与算法 | 事件转换、通用查询、操作请求结构 |
| 延迟、冷却、阶段的使用 | 计时、保存剩余时间、重新激活时恢复 |
| 简短 cue 和相对坐标公式 | 内部编号、坐标、播放去重与资源清理 |
| 必要的特殊机制验证要求 | 通用使用、移动、攻击、等待、无目标和操作拒绝探针 |

框架没有固定技能目录。新状态字段、新条件和新计算依然由模型写代码实现。只开放当前已经实现的 Minecraft 能力。

`compact-output.ts` 使用 YAML 解析器和 Acorn 语法分析，拒绝别名、重复键、危险属性、自定义标签、越界声明和保留标识；不会在 Node 执行模型代码。状态一次声明同时得到 schema 和初值，中文 cue/活动名称由框架映射到内部编号。

转换后的内部产物仍是 `{equipment, behaviorSource}`。生成服务附加固定版本的 `packages/equipment-sdk/runtime/compact-v1.js`，在 QuickJS 中执行。模型原文散列和展开后源码散列分别保存；公共适配器是本地代码，不计为模型输出。装备包一经发布保持不变，不受随后 SDK 修改影响。

## 接口

可对照的类型定义在 `packages/equipment-sdk/src/compact.ts`。

| 接口 | 用途 |
|---|---|
| `g.s / g.p / g.self` | 本次可变状态、只读参数、玩家快照 |
| `g.near / ray / status / block` | 受控查询，失败或无目标需要按返回值处理 |
| `g.push / hurt / damage` | 提交推力、伤害请求或当前攻击伤害修改 |
| `g.after / cancel` | 命名延迟任务，1—1,200 刻 |
| `g.cooldown / ready / remaining` | 冷却与剩余刻数；冷却 0 表示清除 |
| `g.phase / end / cue` | 阶段、结束和反馈；结果反馈必须绑定真实回执 |
| `v.cue / v.t / v.s / v.p` | 当前反馈名、播放刻数、只读状态与参数 |
| `v.particles / sound / text / item` | 粒子、声音、动作栏和物品展示 |

`act` 接收 activate/use/move/hit/timer/tick/receipts；activate 是拿起装备，use 是右键，hit 是自己发起的攻击。收起后宿主取消当前计时器和画面，物品保存剩余任务、冷却与阶段，重新拿起时恢复；不存在离线执行。

通用探针只能证明覆盖到的路径。平台要求至少一条从初始状态出发的探针实际产生世界操作，另测全部操作被拒绝；这些检查不能证明说明完全准确、长期平衡、复杂触发或多人正确性。

## 阿里云调用

当前默认 `deepseek-v4.1-flash-1017`，开启 `low` 思考，单次总输出上限 16,384 token。默认依据是一件通过检查并热加载的真实样本，还不能据此给模型或思考模式排名。

本机已经验证 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` 与现有百炼密钥可用，请求示意如下：

```js
{
  model: "deepseek-v4.1-flash-1017",
  messages,
  enable_thinking: true,
  reasoning_effort: "low",
  max_tokens: 16384
}
```

不发送 `response_format`。上面是 HTTP 请求体字段，不是让模型生成 JSON。Python OpenAI SDK 的 `enable_thinking` 放在 `extra_body` 中，直接 HTTP 和 Node SDK 放在顶层。

| 环境变量 | 默认值 |
|---|---|
| `GE_ALIYUN_MODEL` | `deepseek-v4.1-flash-1017` |
| `GE_ALIYUN_ENABLE_THINKING` | `true` |
| `GE_ALIYUN_REASONING_EFFORT` | `low` |
| `GE_ALIYUN_MAX_TOKENS` | `16384` |
| `GE_ALIYUN_TIMEOUT_MS` | `180000` |

关闭思考可设置 `GE_ALIYUN_ENABLE_THINKING=false`，同时减少总输出预算。模型文档的最大输出是 393,216 token；项目上限与正文目标是不同概念。思考与正文共享输出预算，上限不是预付用量。

阿里云文档还提供业务空间端点 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`。地域、业务空间与密钥必须匹配，本机继续使用已验证的原配置。

## 2026-11-04 实测

输入为羽毛＋紫水晶、意图“踏风回声”。以下都是实际 API 调用，没有手工修改模型行为代码来冒充生成成功。

| 配置 | 首次正文 | 首次思考 | 首次请求耗时 | 整个任务结果 |
|---|---:|---:|---:|---|
| 旧完整模块，high | 约 9,881 token | 25,413 token | 205.6 秒 | 输出过长，已停止该方案 |
| 精简格式，非思考，第一轮 | 1,615 token | 关闭 | 11.0 秒 | 修复两次，仍有绘图错误；失败 |
| 精简格式，low | 1,675 token | 4,589 token | 40.0 秒 | 修复一次，最终正文 1,692 token；总计 55.6 秒，READY |
| 精简格式，非思考，第二轮 | 1,777 token | 关闭 | 10.4 秒 | 中文活动编号适配失败，修复两次仍失败 |

最后一轮暴露的中文活动键问题已由框架修复，并增加自动测试。未再次调用非思考模型，不能宣称修复后成功率已验证。接口在调试期间也有调整，这不是受控模型排名实验。

low 成功任务的两次调用共输入 5,058、输出 8,322 token，其中正文共 3,367、思考共 4,955 token。按北京忙时、忽略缓存折扣的标价约 **0.0767 元**。上述三轮精简格式任务已返回的用量共约 **0.1918 元**，包含两轮失败的调试开销；均为估算，不是账单金额。

成功任务 `89f61568-9d0b-4ee3-9704-60eb01401f51` 已发布并加载到 Paper 26.3；重复加载后定义数量仍为 1，加载期间没有重启服务器。尚未进行真实客户端操作和画面验收。

单轮报告保存在 Git 忽略区 `var/generator/smoke-<taskId>.json`；模型正文、候选和诊断也只保存在忽略区。原始思考正文不持久化。默认最多修复两次，无正文或连续相同失败结果提前停止，不自动重放服务重启时中断的请求。

## 价格和来源

以下为查询当日阿里云华北 2（北京）地域标价，单位人民币 / 百万 token。`deepseek-v4.1-flash-1017` 和 `deepseek-v4.1-flash` 相同：

| 北京时间 | 输入 | 输出（思考＋回答） |
|---|---:|---:|
| 08:00–22:00 | 2 元 | 8 元 |
| 22:00–次日 08:00 | 1 元 | 4 元 |

缓存输入可能进一步打折，免费额度和账户优惠不作承诺。模型选择应继续比较成功发行成本与等待时间，不能只看单价或综合榜单。

- [阿里云 DeepSeek API](https://help.aliyun.com/zh/model-studio/deepseek-api)
- [阿里云模型价格](https://help.aliyun.com/zh/model-studio/model-pricing)
