# Diagnostics build-variant architecture

Woah diagnostics are a build capability, not a production algorithm feature.

## Variant boundary

| Layer | Debug | Release |
| --- | --- | --- |
| Native diagnostics backend | Full writer, bundle exporter, artifact capture, tensor/YUV probes, cross-device reference diagnostics | Minimal no-op contract only where main code needs a type |
| MethodChannel diagnostics | `DiagnosticsChannelBridge` handles create/share/clear bundle methods | Bridge always returns `false`; diagnostic method names are absent from the release implementation |
| Export artifacts | `diagnosticJobId` is populated; pixel/tensor/Face diagnostics may run | `diagnosticJobId = null`; capture objects are not created |
| SAM2 native log capture | Enabled for diagnostic builds | No diagnostics directory or process log capture |
| Flutter diagnostic actions | Visible through `kDebugMode` | Compile-time unreachable and tree-shaken |

Android variant implementations live under:

- `mobile/packages/dance_native/android/src/debug/kotlin/...`
- `mobile/packages/dance_native/android/src/release/kotlin/...`

Production pipeline/tracking/privacy code stays under `src/main` and must not import a debug-only concrete implementation that has no release contract.

## Adding diagnostics

1. Put heavyweight implementations in `src/debug`, not `src/main`.
2. Add a release stub only when shared `src/main` code must reference that type. The stub must not allocate files, threads, queues, trackers, tensor buffers, or artifacts.
3. For hot-path structured events, use `NativeDiagnostics.eventLazy { ... }`. Do not eagerly build `mapOf`, `listOf`, sorted collections, hashes, or mask summaries before a release no-op call.
4. Keep correctness/signature diagnostics separate from algorithm state. Diagnostics must never feed identity, privacy-class, tracking, or renderer decisions back into production state.
5. Add new Flutter diagnostic actions behind `kDebugMode` and add native-only diagnostic channel methods to the debug `DiagnosticsChannelBridge`.

## Release verification

The release build must compile independently of the debug implementations and pass R8 shrinking:

```text
:dance_native:compileReleaseKotlin
:dance_native:assembleRelease
:app:assembleRelease
```

Release artifact checks should verify that debug UI text/method names, diagnostic artifact markers, cross-device reference markers, and high-frequency telemetry event names are absent from the final APK.

The deterministic Face/CPU4 reference diagnostics remain a debug verification authority only. Removing them from release must not change production Full Body behavior or give GPU detections deterministic identity authority.
