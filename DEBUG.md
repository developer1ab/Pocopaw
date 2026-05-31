# Debug Playbook

This repository has a standard, reproducible debug capture flow. Use these entry points instead of ad-hoc logging commands.

## Standard Commands

- Samsung (10 minutes, open latest summary):

```powershell
.\tools\debug_capture_preset.ps1 -Target samsung -Minutes 10 -NoScreenshot
```

- OPPO (10 minutes, open latest summary):

```powershell
.\tools\debug_capture_preset.ps1 -Target oppo -Minutes 10 -NoScreenshot
```

- Generic explicit serial:

```powershell
.\tools\system_debug_capture_and_open.ps1 -Device <SERIAL> -AppId com.atombits.pocopaw -Minutes 10 -NoScreenshot
```

## VS Code Tasks

- `capture-oppo-standard-10min`
- `capture-samsung-standard-10min`
- `capture-debug-open-summary`
- `capture-samsung-open-summary`

## Artifacts (logs/captures)

- `summary_*.txt`
- `summary_*.json`
- `logcat_filtered_*.txt`
- `prototype_store_*.json`
- `activity_focus_*.txt`
- `ui_dump_*.xml` (unless `-NoUiDump`)
- `screenshot_*.png` (unless `-NoScreenshot`)

## Fast Triage Order

1. Read `summary_*.txt` first (lifecycle, runtime, failures).
2. Check `prototype_store_*.json` (current runtime + executionEvents tail).
3. Read `logcat_filtered_*.txt` for AndroidRuntime, run-as, accessibility, capture failures.
4. Validate UI truth with `ui_dump_*.xml` and screenshot when needed.

## Incident Template

```text
Incident: <short title>
Time Window: <local time range>
Device: <serial/model>
AppId: com.atombits.pocopaw
Capture Command: <exact command>
Capture Folder: logs/captures/<files>

Observed Symptom:
- <what user saw>

State Truth (summary/store):
- stage=<...>
- execution_runtime_present=<...>
- execution_runtime_lifecycle=<...>
- execution_runtime_summary=<...>
- key execution events=<...>

System Truth (logcat):
- <key lines>

UI Truth:
- <screen reality>

Root Cause Hypothesis:
- <single cause>

Fix Direction:
- <smallest high-confidence fix>

Validation:
- <rerun and result>

Residual Risk:
- <remaining uncertainty>
```

## Notes from LamdaAI/Prototype Experience

- Keep one default capture command and avoid manual command mixing.
- Keep artifact naming stable under `logs/captures` so triage order is predictable.
- Always package summary + store + filtered logcat together for each incident.
