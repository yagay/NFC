# NFC Expert Pro

面向 OxygenOS / ColorOS 的 NFC UID 模拟与诊断工具。应用通过 LSPosed 在系统 NFC 进程中发现并验证 RF 配置写入路径，以真实的原生返回结果判断模拟是否生效。

## 当前版本

- 应用版本：`1.0.56`（versionCode `57`）
- Hook 构建：`40`
- Provider 协议：`7`

## 主要能力

- 读取并保存 4/7/10 字节 NFC UID。
- APPLY、STOP 和恢复流程均绑定命令 generation、控制器 epoch 与 NFC 进程 PID。
- 通过原生 RF 写入结果 `0` 确认成功，不把 Binder 调用或刷新触发本身当作成功。
- 自动发现、学习并缓存与当前系统指纹和 NFC APK 版本匹配的 Hook profile。
- 支持导出 Provider 状态、运行日志和 Hook 诊断信息。
- 界面黑暗模式跟随系统；保留原有系统栏颜色策略，并处理状态栏与按键导航栏安全区域。

## 运行状态

- `APPLYING`：命令已发布，等待与本次请求严格对应的 RF 写入结果。
- `ACTIVE`：已收到同 generation、PID 和 epoch 的模拟 RF 成功结果。
- `STOPPING`：正在等待恢复原始 RF 配置。
- `INACTIVE`：已收到严格对应的原始 RF 成功结果。
- `WAIT_FOR_REPLAY`：刷新流程结束但精确 RF 回放稍晚到达；最多额外等待 1.2 秒，避免生命周期时序造成误报。
- `FAILED`：超时、原生返回失败或身份信息不匹配。

## 项目结构

- `MainActivity`：Activity 生命周期、权限入口和顶层界面装配。
- `NfcAppScreen`：Compose 页面与交互展示。
- `SimulationCoordinator`：应用侧 APPLY / STOP 流程编排。
- `DiagnosticExporter`：诊断导出。
- `NfcInjectionModule`：NFC 进程 Hook 安装与关键生命周期协调。
- `HookDiscoveryEngine`：RF 写入候选发现与评分。
- `RfPayloadEngine`：RF payload 识别与安全改写。
- `HookConfigStore` / `HookStateWriter`：Provider 配置读取与状态发布。
- `SimulationResultPolicy`：严格的结果归属和成功判定。

更完整的设计说明见 [Hook 架构](docs/HOOK_ARCHITECTURE.md) 和 [运行时优化](docs/RUNTIME_OPTIMIZATION.md)。

## 构建与安装

1. 使用 JDK 17 执行 `./gradlew assembleDebug testDebugUnitTest`。
2. 安装 APK，在 LSPosed 中启用模块，作用域仅选择“系统 NFC / `com.android.nfc`”。
3. 重启 NFC 进程或手机，使新 Hook 构建被加载。
4. 首次验证时执行一次 APPLY、STOP、再次 APPLY，并导出完整诊断。

版本升级后不能只覆盖安装就判断 Hook 是否更新；诊断中的 Hook build 应为 `40`，Provider protocol 应为 `7`。

## 自动检查

GitHub Actions 会构建 APK、运行 JVM 单元测试，并校验：

- versionCode / versionName 与预期一致；
- Xposed 入口和模块元数据存在；
- 模块作用域保持为 `com.android.nfc`；
- APK 已签名并通过 zipalign 检查。

## 安全边界

本项目直接作用于系统 NFC 进程。结构重构必须保持命令协议、generation/PID/epoch 归属与 RF 原生结果判定不变。没有设备日志证明收益时，不拆分 Hook 安装、生命周期恢复和早期 RF 回放之间的时序关键路径。
