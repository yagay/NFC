# Runtime Optimization

Current runtime behavior in 1.0.56:

- UI runtime state is driven primarily by `ConfigProvider.notifyChange()` and a `ContentObserver`.
- Stale runtime state is rejected by Provider generation, NFC PID, controller epoch, and update-time freshness checks. There is no periodic PID watchdog.
- Runtime state refresh is independent from log refresh; logs are polled only while the log panel is enabled.
- Log rendering uses a bounded Compose state window instead of splitting the full log string during composition.
- A verified RF hook profile takes the fast path and skips diagnostic trigger-candidate DEX scanning.
- Controller reinitialization retries only transient binder/exception stages; structural transaction/descriptor failures fail fast so the app can immediately use its existing fallback path.
- Persistent interactive root shells are intentionally not used. Short root commands remain isolated `su -c` processes to keep command framing, timeouts, and failure recovery simple.
- Cross-call NCI fragment assembly is intentionally not enabled until device logs prove that the Java RF-config hook receives fragmented NCI frames.
- `MainActivity` delegates Compose rendering to `NfcAppScreen`, command orchestration to `SimulationCoordinator`, and exports to `DiagnosticExporter`; this keeps UI changes away from the NFC command protocol.
- Hook-side Provider access is isolated in `HookConfigStore` and `HookStateWriter`, while `SimulationResultPolicy` owns generation/PID/epoch result attribution.
- If lifecycle recovery finishes immediately before the matching RF callback, the app enters `WAIT_FOR_REPLAY` for at most 1.2 seconds. This is a bounded grace period, not a relaxed success rule: only an exact native-success replay can complete the request.
