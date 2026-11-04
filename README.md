# Minecraft 生成装备

在 Minecraft Java / Paper 里，用材料、玩家意图和出生环境生成装备代码。属性名称、状态字段、条件、阶段和动画公式由模型编写，宿主只开放已实现的世界接口。

目前是单机测试服 Alpha：原生 QuickJS、物品状态、活动与计时器、世界操作、表现、生成 API、验证、打包、热加载、领取和记录已接通。真实模型已生成一件通过检查的装备并完成 Paper 热加载；客户端画面、双人联机、真实材料经济和完整故障恢复尚未验收。详细进度见 [实施步骤](docs/implementation-progress.md)，操作见 [试玩指南](docs/playtest.md)。

## 环境与构建

需要 Node 24、pnpm 11、JDK 25，以及 C 编译器（macOS 的 Xcode Command Line Tools 或 Linux 的 gcc/clang）。Gradle Wrapper、Paper 和 QuickJS 版本已固定。首次构建会下载并校验 QuickJS 源码；macOS 生成同时支持 Intel / Apple Silicon 的原生库，Linux 编译本机架构。Windows 原生构建未实现。

```bash
pnpm install --frozen-lockfile
pnpm build
pnpm test
```

如果系统 Java 不适用，可以设置 `GE_JAVA_HOME` 或 `JAVA_HOME`。本机开发脚本也识别 Git 忽略区 `var/toolchains/active-java-home.txt` 指定的工具链。当前这台 macOS 机器使用 Rosetta 下的 x64 JDK 25 完成构建验证。

主要产物：

- `java/paper-plugin/build/libs/GenerativeEquipment-0.1.0.jar`
- `java/validator-cli/build/libs/validator-cli.jar`

## 运行

把 `.env.example` 复制为 `.env`，设置随机 `GE_INTERNAL_TOKEN`（至少 32 字符）、阿里云密钥和模型。默认 `deepseek-v4.1-flash-1017`、`low` 思考，总输出上限 16,384 token（包含思考），正文目标 1,000—2,000 token。调用方式、计费和实测记录见 [模型接入说明](docs/model-provider.md)。真实调用和每次修复都会消耗 token，最多修复次数由世界配置控制且宿主硬上限为 2 次。没有代码正文或修复结果完全相同时会提前停止；不会自动重放服务重启时中断的模型调用。

两个终端分别启动：

```bash
pnpm dev:generator
pnpm dev:paper
```

启动脚本准备 Paper 26.3 build 240 并安装构建的插件。第一次启动要自行阅读 Minecraft EULA，在 `var/paper/eula.txt` 中确认 `eula=true`。Java 版 26.3 客户端连接 `127.0.0.1:25565`。其他已有 Paper 服务器可将插件 JAR 放入 `plugins/`，配置相同的内部令牌和本机生成服务地址，再启动服务器。

插件安装或升级需要重启 Paper；装备定义下载、校验、编译和注册在服务器运行期间进行，不执行插件 reload。已经缓存的定义不依赖模型服务在线。

首次试玩用**测试额度**选择材料，不扣真实背包材料。`generation.test-credits=false` 时拒绝新建，避免把未验收的材料扣除当作已实现。原版物品的丢弃、拾取、入箱和转交沿用 Minecraft；未验证的合成、铁砧等转换会受限。

## 代码入口

| 位置 | 职责 |
|---|---|
| `contracts/schemas/` | HTTP、生成源文件、行为和表现的数据契约 |
| `contracts/capabilities/v1/host.json` | 当前事件、查询、操作、表现及预算说明 |
| `services/generator/src/main.ts` | 生成服务入口 |
| `services/generator/src/app.ts` | 鉴权、依赖组装和后台 worker |
| `services/generator/src/adapters/aliyun/model.ts` | 模型提示、调用、修复与真实 token 用量 |
| `services/generator/src/adapters/aliyun/compact-output.ts` | 解析 YAML 短声明、展开状态结构和平台探针，不在 Node 执行代码 |
| `packages/equipment-sdk/runtime/compact-v1.js` | 公共计时、冷却、阶段、回执与相对坐标绘图适配器 |
| `services/generator/src/adapters/validator-process.ts` | 语法预检和独立 Java 验证进程 |
| `services/generator/src/adapters/definitions.ts` | 打包、不可变发布、交付与观察记录 |
| `java/runtime-quickjs/` | Java/C 连接层、内存和中断控制 |
| `java/core/` | 状态、活动、决策、操作回执、装备包校验 |
| `java/paper-plugin/` | 命令、界面、原版物品、世界和画面适配 |
| `java/validator-cli/` | 复用正式运行器执行隔离候选测试 |
| `tests/fixtures/equipment/` | 明确标记为手写的宿主测试程序 |

手写公共接口和关键状态转换有中文注释。生成的 DTO 以 schema 为源，不直接手改。

## 配置与存储

`worlds/mist-isle/materials.json` 配置可用于生成的材料及语义，`generation-policy.json` 配置材料数量、意图输入和修复次数。每个新任务读取当前配置；相同 taskId 重试使用冻结输入。材料语义提供灵感，不映射到预制技能。

`/ge create <意图>` 允许自由文本，中文每字、英文每词计数，最多 10 个；另有 UTF-8 字节上限。`/ge tasks`、`/ge claim <任务编号>`、`/ge retry <任务编号>`、`/ge cancel <任务编号>` 管理自己的任务。`/ge inspect` 和 `/ge records` 查看当前主手装备。

插件只激活主手装备。当前支持实体查询、视线查询、方块查询、推力、伤害修改和受控伤害请求，默认拒绝 PvP。表现支持原版粒子、声音、文本和展示实体。没有松开右键事件、任意系统调用、离线能力、玩家长期义务或所有权锁。

模型输出 YAML 短声明与原样 `act(g,e)`、`draw(v)` 代码。声明在两行 `---` 之间，代码无需字符串转义。框架展开状态 schema、事件接入、计时器和绘图协议；HTTP、存储和运行器边界继续使用内部 JSON。参数、状态字段、规则和动画公式仍由模型创作。

生成服务保存在 `var/generator/`：SQLite、原始候选、验证报告与已发布 ZIP。物品自有状态保存在原版 ItemStack/PDC，已确认定义缓存、交付日志和停用记录位于插件数据目录。密钥、候选、数据库、存档和编译产物均不提交 Git。

## API 与验证

API 默认监听 `127.0.0.1:8765`。除 `/health/live` 外均要求内部 Bearer 令牌，凭证绑定 hostId。接口入口见 `services/generator/src/http/routes/index.ts`，完整路由见 `contracts/openapi.yaml`。

```bash
pnpm contracts:check
pnpm check:design
pnpm format:check
```

生成任务只有通过检查并成功发布才进入 READY；领取时还要验证和加载装备包。检查包括数据结构、宿主能力、状态 schema、QuickJS 编译、平台基本操作探针及专属场景、世界操作被拒绝和表现返回。有限场景测试不能证明装备在所有情况下正确或平衡；真实客户端和联机验收单独记录。

`node --env-file=.env scripts/smoke-generation.mjs` 会进行真实、付费的生成测试，把结果写入 `var/generator/latest-smoke.json`。普通 `pnpm test` 使用手写 fixture 和测试替身，不调用模型。

交付采用固定 deliveryId 和持久化准备记录。崩溃留下 PREPARED 时进入人工核对，不自动重发；APPLIED/ACKNOWLEDGED 只代表曾观察到放入背包，并不保证世界存档已刷盘。生成代码超时或异常会停止相关能力，已经发生的世界效果不自动回滚。

## 设计依据

正式设计仍位于仓库外的 `../discussion/outputs/v0.2/`，由 `design-reference.json` 记录路径和散列。仓库可独立构建，设计文件不参与打包。后续多人、真实材料经济和旧装备再生成按阶段继续验收。
