# QuickJS 原生桥

固定 QuickJS 2026-06-04，源码下载地址与 SHA-256 在 `scripts/build-native.mjs`。构建时校验源码，编译 macOS universal 或本机 Linux 原生库，随插件与验证器打包。许可证随原生库分发。

JNI 只交换 UTF-8 JSON 和本机编译的字节码。每次调用新建 runtime/context，内存上限 8 MiB、栈上限 512 KiB，执行中断覆盖初始化、查询、处理器和结果序列化。编译使用独立的 2 秒预算。未安装文件、网络、OS 或任意 native 模块；唯一导入是受信任 `@ge/sdk`。

QuickJS 的模块求值依赖内部 Promise 支持，因此保留对应 intrinsic，移除脚本的全局 Promise 入口，并拒绝异步模块与异步返回。Date、RegExp、eval、Function 和 Math.random 入口被移除；模型源码还经过 Acorn 语法预检。每次调用从显式状态开始，不能保存 JS 全局变量继续下一次调用。

原生桥测试包含死循环、递归、内存超限、SDK 导入、查询额度、非法 JSON 和 render 越权输出。计时使用 monotonic clock。达到 wall-clock 截止时间可以放弃未提交决策，但无法消除 JVM 暂停与操作系统调度造成的延迟；初始 1 毫秒性能目标尚未稳定达到。

原生引擎自身漏洞和进程崩溃仍是风险。生成候选先在独立验证进程运行；正式游戏中的原生运行器仍与 Paper 同进程。Windows 原生构建尚未实现。
