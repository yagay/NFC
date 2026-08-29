# NFC 门禁诊断 / UID 模拟实验

这是一个面向已 Root Android 设备的 NFC / LSPosed 诊断项目。

## V12 重点

V12 不再把 `setHceTypeAConfig(...)=true` 视为“UID 已经固定”。状态被拆成两层：

- **HCE Native**：只表示 Java / JNI HCE 配置入口接受了调用。
- **RF NFCID1**：只有观察到候选 RF/NCI 配置缓冲区中的 `LA_NFCID1 (0x33)`，并实际改写为目标 UID 后才进入 RF 配置状态。

V12 会：

1. 继续 Hook OxygenOS/NXP 的 `setHceTypeAConfig(boolean, byte[], byte[], byte[])` 作为已验证的 HCE 入口；
2. 扫描 `NxpNativeNfcManager` / `StNativeNfcManager` 中带 `byte[]` 的 config/vendor/raw/rf/write 候选方法；
3. 仅当参数中已经存在合法 `LA_NFCID1 (0x33)` TLV 时进行改写；
4. 支持 4 / 7 / 10 字节 NFCID1；
5. 不主动发送未知 raw vendor command，也不凭空构造未观察到的 NCI 包；
6. 导出 V12 完整诊断，包含 `RF:`、`NFCID1`、`CORE_SET_CONFIG` 等关键日志。

## 状态含义

- `WAITING`：尚未观察到真实 RF NFCID1 配置路径。
- `OBSERVED`：发现了 `LA_NFCID1`，但模拟未启用。
- `APPLYING`：已把现有 `LA_NFCID1` 替换为目标 UID，正在调用原始 native 方法。
- `RF_CONFIG_ACCEPTED`：携带改写后 NFCID1 的原始 native 调用已返回成功/非布尔结果。
- `FAILED`：参数长度或 native 调用失败。

`RF_CONFIG_ACCEPTED` 仍然不等同于门禁读卡器最终一定读到目标 UID。最终 RF 行为必须由外部读卡器验证。V12 的目标是一次性定位真正的 `LA_NFCID1` 配置路径，而不是继续依赖 HCE success。
