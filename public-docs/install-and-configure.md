# Install and Configure pocopaw

This guide describes the minimum setup needed to build and run the public project locally.

## 1. Prerequisites

- Android Studio
- Android SDK 33
- JDK 8 or newer
- ADB available in PATH
- At least one Android device for testing

## 2. Open the project

Clone the repository and open the project root in Android Studio.

If `local.properties` is missing, let Android Studio generate it automatically or create it manually with your local Android SDK path.

## 3. Configure private keys locally

The project reads provider secrets and related configuration from the root `local.properties` file.

Do not commit real secret values into the repository.

Fill in only the providers you actually plan to use.

### Common secret entries

```properties
DEEPSEEK_API_KEY=
OPENAI_API_KEY=
GEMINI_API_KEY=
QWEN_VISION_API_KEY=
OPENSEARCH_API_KEY=
GOOGLE_SEARCH_API_KEY=
GOOGLE_SEARCH_ENGINE_ID=
```

### Optional override entries

```properties
DEEPSEEK_ENDPOINT=
DEEPSEEK_MODEL=
DEEPSEEK_MODEL_FAST=
DEEPSEEK_MODEL_EXPERT=

OPENAI_ENDPOINT=
OPENAI_MODEL=
OPENAI_MODEL_FAST=
OPENAI_MODEL_EXPERT=

GEMINI_ENDPOINT=
GEMINI_MODEL=
GEMINI_MODEL_FAST=
GEMINI_MODEL_EXPERT=

QWEN_VISION_ENDPOINT=
QWEN_VISION_MODEL=
QWEN_VISION_MODEL_FAST=
QWEN_VISION_MODEL_EXPERT=

OPENSEARCH_ENDPOINT=
OPENSEARCH_WORKSPACE=
OPENSEARCH_SERVICE_ID=

GOOGLE_SEARCH_ENDPOINT=
```

Keep your real values in a private local worksheet, password manager, or another local-only note. Do not publish them.

## 4. Build the debug APK

From the project root:

```powershell
.\gradlew :app:assembleDebug
```

The default debug APK output is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 5. Install and launch

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.atombits.pocopaw/.MainActivity
```

## 6. First-run readiness

For the local execution chain to work well, the most important first-run surfaces are:

- accessibility service state,
- screen capture permission,
- usage access,
- and optional Shizuku preparation.

Without these, the project can still open and render product surfaces, but the execution path will be incomplete.

## 7. 蒲公英分发

| 项目 | 值 |
|------|-----|
| 应用 | https://www.pgyer.com/pocopaw-android |
| 包名 | com.atombits.pocopaw |
| API Key | 79bbeb1f82c2d6e35975d1110f296b11 |

**命令行上传**（Windows，需用 Python）：

```powershell
python -c "import requests; r=requests.post('https://www.pgyer.com/apiv2/app/upload', files={'file': open('app/build/outputs/apk/debug/app-debug.apk', 'rb')}, data={'_api_key': '79bbeb1f82c2d6e35975d1110f296b11', 'buildUpdateDescription': '版本说明'}); print(r.json())"
```
