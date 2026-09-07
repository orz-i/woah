# Provisioned inference assets

Large model binaries are intentionally not committed here. Before an iOS
inference build, stage the repository-local YOLO LiteRT model with:

```text
python tools/release/sync_ios_yolo_model.py
```

The provisioning tool verifies the tracked tensor/file contract, copies the
model atomically, and writes a SHA-256 sidecar. Both generated files are
ignored by Git; this README keeps the CocoaPods resource bundle structurally
present even before model provisioning.
