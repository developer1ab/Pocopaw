# Debug Capture Standard

## Purpose

Use one consistent capture path for runtime/debug incidents, so evidence is reproducible and analysis time is reduced.

## Standard Entry Point

Use this command as the default capture workflow:

```powershell
.\tools\system_debug_capture.ps1 -Device <SERIAL> -Minutes 10 -AppId com.atombits.pocopaw
```

Example:

```powershell
.\tools\system_debug_capture.ps1 -Device R5CN503C8XY -Minutes 10 -AppId com.atombits.pocopaw
```

## Output Location

By default, artifacts are written under:

- logs/captures

Core files:

- summary_*.txt
- summary_*.json
- logcat_filtered_*.txt
- prototype_store_*.json
- toolspace_catalog_*.json
- activity_top_*.txt
- activity_activities_*.txt
- window_windows_*.txt
- activity_focus_*.txt
- app_files_*.txt
- ui_dump_*.xml (unless disabled)
- screenshot_*.png (unless disabled)

## Fast Triage Order

1. Open summary_*.txt first and check:
- execution_preparation_present
- execution_runtime_present
- execution_runtime_lifecycle
- execution_runtime_summary
- jd_capability_present (or target capability present)
- failures section

2. Open prototype_store_*.json and inspect:
- currentState.stage
- currentExecutionRuntime
- executionEvents (last few records)
- pendingProcessRecoveryContext / latestCompletedProcessReviewContext

3. Open logcat_filtered_*.txt for runtime errors:
- AndroidRuntime
- run-as failures
- CaptureService / accessibility path failures
- app/package launch and focus transitions

4. Use ui_dump_*.xml and screenshot_*.png to verify UI truth at capture time.

## Capture Variants

Skip UI dump:

```powershell
.\tools\system_debug_capture.ps1 -Device <SERIAL> -Minutes 10 -NoUiDump
```

Skip screenshot:

```powershell
.\tools\system_debug_capture.ps1 -Device <SERIAL> -Minutes 10 -NoScreenshot
```

## Incident Report Template

Use this template for each incident:

```text
Incident: <short title>
Time Window: <local time range>
Device: <serial/model>
AppId: com.atombits.pocopaw
Capture Command: <exact command>
Capture Folder: logs/captures/<timestamp-related files>

Observed Symptom:
- <what the user saw>

State Truth (summary/store):
- stage=<...>
- execution_runtime_present=<...>
- execution_runtime_lifecycle=<...>
- execution_runtime_summary=<...>
- relevant execution event(s)=<...>

System Truth (logcat/failures):
- <key error or warning lines>

UI Truth (screenshot/ui_dump):
- <what screen actually showed>

Root Cause Hypothesis:
- <single causal explanation>

Fix Direction:
- <smallest high-confidence fix>

Validation:
- <what was rerun and result>

Residual Risk:
- <remaining uncertainty>
```
