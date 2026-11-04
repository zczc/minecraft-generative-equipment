# Minecraft 生成装备系统

基于 Minecraft Java / Paper 的 LLM 生成装备项目。当前交付 **S0：代码骨架和基础服务**，尚不能生成可玩的装备。

## 当前已经实现

- 可配置的 8 种原版材料、材料语义与生成策略；新任务读取当前配置，旧任务保留冻结快照。
- 中英文混合意图计数，默认最多 10 字／词，另外限制输入字节数。
- JSON Schema、OpenAPI 和从同一契约生成的 TypeScript 类型、Java DTO。
- 本机生成服务：宿主鉴权、能力 profile 注册、任务入库、幂等、取消和中断恢复。
- 装备脚本 SDK、Java core ports、Paper 命令入口与异步服务诊断。
- Gradle 多模块构建、插件 JAR 和独立验证器 JAR。

QuickJS、真实阿里云生成、候选打包与验证、世界效果、材料消耗和装备交付仍明确返回 `NOT_IMPLEMENTED`。任务入库成功不是生成成功；S0 worker 会把任务标为 FAILED，不会生成假装备。`/v1/health/ready` 返回 503 正是这一状态。

## 项目结构

```text
contracts/             API 与数据契约，唯一字段定义来源
packages/protocol/     TypeScript 类型、校验、计数和散列
packages/equipment-sdk/ 装备 onEvent / render 接口与纯函数
services/generator/    HTTP、SQLite、材料快照与任务编排
java/protocol/         Java 派生 DTO、校验与计数
java/core/             宿主接口与激活凭据
java/runtime-quickjs/  明确不可执行的 S0 运行器适配
java/paper-plugin/     Minecraft 插件入口
java/validator-cli/    验证进程协议，S0 不报告通过
worlds/mist-isle/      世界观、材料和生成策略配置
tests/                 契约与服务集成测试
scripts/               构建、协议生成与本机测试服工具
var/                   本机运行数据，Git 忽略
```

手写公共接口和关键流程包含中文注释，说明输入输出、线程边界、失败行为和设计原因。生成的类型文件不手工编辑，运行 `pnpm contracts:generate` 更新。

## 构建与测试

需要 Node.js 24、pnpm 11.25.0、Java 25。Gradle Wrapper 已包含在仓库中，Paper API 固定为 `26.3.build.240-stable`。Java 可以由 `JAVA_HOME` 或 `GE_JAVA_HOME` 指定。

```sh
pnpm install --frozen-lockfile
pnpm build
pnpm test
```

单独检查可使用 `pnpm test:contracts`、`pnpm test:integration` 和 `pnpm test:java`。`pnpm contracts:check` 验证 schema 与派生代码没有漂移。

## 启动生成服务

复制 `.env.example` 为 `.env`，设置自己的 `GE_INTERNAL_TOKEN`（至少 32 字符）。S0 不需要阿里云密钥，也不会进行收费模型调用。

```sh
pnpm dev:generator
```

服务监听 `127.0.0.1:8765`。`GET /health/live` 无需鉴权；其余接口都需要 `Authorization: Bearer <GE_INTERNAL_TOKEN>`。完整接口见 [OpenAPI](contracts/openapi.yaml)。

| 接口                                             | 当前作用                                      |
| ------------------------------------------------ | --------------------------------------------- |
| `GET /v1/worlds/mist-isle/materials`             | 查看启用材料和输入策略                        |
| `POST /v1/hosts/local-paper/capability-profiles` | 注册不可变能力清单                            |
| `POST /v1/generations`                           | 提交任务，需 `Idempotency-Key` 与 taskId 相同 |
| `GET /v1/generations/{taskId}`                   | 查询任务及明确的失败原因                      |
| `POST /v1/generations/{taskId}/cancel`           | 取消未完成任务                                |

服务启动前读取环境变量；材料配置每个新任务重新读取。配置文件无效会报错，不回退成硬编码默认材料。

## 在 Minecraft 中安装插件入口

构建产物是 `java/paper-plugin/build/libs/GenerativeEquipment-0.1.0.jar`。

1. 保持生成服务运行，在另一个终端执行 `pnpm dev:paper`。
2. 工具下载固定 Paper 版本并校验 SHA-256，把插件放入 `var/paper/plugins/`。
3. 首次启动按提示阅读 Minecraft EULA，并由你设置 `var/paper/eula.txt`。
4. 再次执行 `pnpm dev:paper`，使用匹配版本的 Minecraft Java 客户端连接 `localhost:25565`。
5. `/ge` 查看入口，管理员用 `/ge admin diagnose` 查看服务连接；`/ge inspect` 读取手持物品的规则引用。

`/ge create`、领取和实际装备能力在 S0 尚未实现，命令会明确提示，既不扣材料也不发物品。首次安装插件需要启动服务端；生成装备无需重启的能力属于后续 S2/S3 验收范围。

## 修改材料

编辑 `worlds/mist-isle/materials.json` 中的条目：

```json
{
  "itemId": "minecraft:iron_ingot",
  "enabled": true,
  "name": "铁锭",
  "gameFacts": "用于合成工具、盔甲等物品。",
  "associations": ["重量", "承受", "结构"]
}
```

`gameFacts` 是游戏事实，`associations` 是设计联想。它们不是预制技能，也不会自动给宿主增加新操作。每次投入的数量来自玩家提交；服务把数量与配置描述组合成模型输入快照。`generation-policy.json` 管理材料种数、每种数量、输入字节数与修复次数等限制。

## 设计依据与下一阶段

`design-reference.json` 锁定讨论区 v0.1 两份设计的路径与 SHA-256。按项目约定，正式文档保存在仓库外的 `../discussion/outputs/v0.1/`，不混入代码仓库。独立克隆后应另行获取对应设计；构建和测试不依赖这些外部文档。已有本地文档时可运行 `pnpm check:design` 核对。

下一阶段是 S1：验证 QuickJS 的 Java 集成、隔离运行、同步事件、内存和中断控制；随后接入游戏操作与表现，再连接真实阿里云生成。手写 fixture 和模拟测试不能代替真实生成或 Minecraft 联机验证。
