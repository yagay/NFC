# 实现 NFC UID 模拟与智能切卡屏蔽 (LSPosed)

根据分析结果，我们将完善 `experimental-lsposed` 模块，通过 Hook 系统 NFC 进程实现物理层 UID 注入，并屏蔽 Oplus (ColorOS) 的智能切卡干扰。

## 方案设计

1.  **Gradle 配置**：将 `experimental-lsposed` 注册为 Gradle 模块，并配置 LSPosed API 依赖。
2.  **Manifest 配置**：设置模块为 Xposed 模块，并指定目标作用域。
3.  **Hook 逻辑**：
    *   拦截 `NativeNfcManager.doInitialize`：注入 NCI 配置 `LA_NFCID1` (UID)。
    *   拦截 `NfcSwitchCardDispatcher`：防止系统自动切换卡片。
    *   拦截 `NfcFeatureManager`：强制禁用 `SMART_SWITCH_CARD` 功能。

## 待变更文件

### [Gradle 配置]

#### [MODIFY] [settings.gradle.kts](file:///C:/Users/jieei/AndroidStudioProjects/NFC/settings.gradle.kts)
注册 `:experimental-lsposed` 模块。

#### [NEW] [experimental-lsposed/build.gradle.kts](file:///C:/Users/jieei/AndroidStudioProjects/NFC/experimental-lsposed/build.gradle.kts)
配置 LSPosed 编译环境和依赖。

### [LSPosed 模块实现]

#### [NEW] [experimental-lsposed/src/main/AndroidManifest.xml](file:///C:/Users/jieei/AndroidStudioProjects/NFC/experimental-lsposed/src/main/AndroidManifest.xml)
声明 Xposed 模块元数据。

#### [MODIFY] [NfcDiagnosticsModule.java](file:///C:/Users/jieei/AndroidStudioProjects/NFC/experimental-lsposed/src/main/java/com/example/nfcdoorcard/xposed/NfcDiagnosticsModule.java)
实现核心 Hook 逻辑。

## 验证计划

### 编译验证
- 运行 `./gradlew :experimental-lsposed:assembleDebug` 确保代码无误。

### 手动验证
1. 安装生成的 APK。
2. 在 LSPosed 管理器中激活模块，并勾选 **“NFC 服务” (com.android.nfc)**。
3. 重启手机或强制停止 NFC 服务。
4. 使用另一台手机或读卡器测试，检查 UID 是否变为 `AA BB CC DD`。
5. 检查 `logcat` 日志中是否有 `NfcUIDSim: UID 物理层注入完成` 字样。
