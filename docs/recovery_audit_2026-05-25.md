# Recovery Audit 2026-05-25

## Purpose

This document records the Copilot session operations and the stage-2 recovery actions used to reconstruct the workspace toward the 2026-05-25 18:30 local state.

## Transcript Source

- Transcript file: `C:/Users/HP/AppData/Roaming/Code/User/workspaceStorage/4de52dee75619b20ac60d707c2c0e845/GitHub.copilot-chat/transcripts/08aa5caa-7640-412d-9f9e-3d0acb6cc306.jsonl`
- Parsed line count: 1143
- Parsed tool calls: 90

## Tool Call Inventory

- `run_in_terminal`: 39
- `file_search`: 20
- `read_file`: 13
- `grep_search`: 7
- `list_dir`: 3
- `apply_patch`: 3
- `semantic_search`: 3
- `get_terminal_output`: 1
- `get_errors`: 1

## High-Risk Git Timeline (Extracted)

- 2026-05-25T10:25:51Z: `git switch --orphan main-clean`
- 2026-05-25T10:26:18Z: `git branch -D main; git branch -m main; git remote remove prototype-refactor-legacy`
- 2026-05-25T10:26:46Z: `git stash pop`
- 2026-05-25T10:27:46Z: `git stash drop "stash@{0}"`
- 2026-05-25T10:28:50Z: remove pseudo refs and query `git log --all`
- 2026-05-25T10:32:24Z: `git fetch legacy-recover --prune; git branch -r`
- 2026-05-25T10:32:53Z: `git remote remove legacy-recover`

Note: Full extracted timeline was generated from transcript parsing in terminal and used as audit input.

## Stage-2 Recovery Manifest

- Manifest file: [temp_restore_manifest_20260525_1830.json](temp_restore_manifest_20260525_1830.json)
- Manifest count: 121 files
- Time cap: 2026-05-25 18:30 local
- Missing files vs manifest after restore: 0
- Extra files beyond manifest scope (current): 20

## Namespace State During Stage-2

Pre-shaping counts:

- `pocopaw` main tree: 34 files
- `popopaw` main tree: 32 files
- `prototyperefactor` main tree: 0 files

Shaping action executed:

- Move only non-conflicting files from `com/atombits/popopaw` to `com/atombits/pocopaw`
- Do not overwrite conflicting destination files
- Keep backup and conflict report

Shaping result:

- Moved: 21
- Conflicts kept for manual review: 12
- Missing roots: 1 (`androidTest popopaw`)
- Remaining `popopaw` main files: 12
- Remaining `popopaw` test files: 0
- Current `pocopaw` main files: 54
- Backup root: `C:/Users/HP/AndroidStudioProjects/Pocopaw_shape_backup_fix_20260525-190121`
- Detailed move/conflict log: `C:/Users/HP/AndroidStudioProjects/Pocopaw_shape_backup_fix_20260525-190121/popopaw_to_pocopaw_report.txt`

## Files Verified as Recovered to 18:xx Window

- [app/build.gradle](app/build.gradle)
- [app/src/main/java/com/atombits/pocopaw/ProviderRuntimeConfig.kt](app/src/main/java/com/atombits/pocopaw/ProviderRuntimeConfig.kt)
- [app/src/main/java/com/atombits/pocopaw/SemanticPrototypeClient.kt](app/src/main/java/com/atombits/pocopaw/SemanticPrototypeClient.kt)
- [docs/design11.md](docs/design11.md)
- [scripts/system-debug-capture.js](scripts/system-debug-capture.js)
- [scripts/clear-process-store.js](scripts/clear-process-store.js)
- [tools/system_debug_capture.ps1](tools/system_debug_capture.ps1)

## Outstanding Manual Review Items

- 12 `popopaw -> pocopaw` file conflicts remain unresolved by design (non-overwrite policy).
- 42 files currently outside the 18:30 manifest scope remain present and require keep/remove decision.
