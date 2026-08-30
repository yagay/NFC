# Runtime Optimization

Current runtime behavior after the 1.0.24 refactor:

- UI runtime state is driven primarily by `ConfigProvider.notifyChange()` and a `ContentObserver`.
- A low-frequency 20-second NFC PID watchdog remains as an external stale-process check.
- Runtime state refresh is independent from log refresh; logs are polled only while the log panel is enabled.
- Log rendering uses a bounded Compose state window instead of splitting the full log string during composition.
- A verified RF hook profile takes the fast path and skips diagnostic trigger-candidate DEX scanning.
- Controller reinitialization retries only transient binder/exception stages; structural transaction/descriptor failures fail fast so the app can immediately use its existing fallback path.
- Persistent interactive root shells are intentionally not used. Short root commands remain isolated `su -c` processes to keep command framing, timeouts, and failure recovery simple.
- Cross-call NCI fragment assembly is intentionally not enabled until device logs prove that the Java RF-config hook receives fragmented NCI frames.
