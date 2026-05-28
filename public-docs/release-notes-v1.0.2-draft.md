# pocopaw v1.0.2 Release Notes

## Release Artifact
- Version Name: 1.0.2
- Version Code: 3
- Variant: release
- File: app/build/outputs/apk/release/app-release-unsigned.apk
- SHA256: 3F1A6ED304E8A9664A2FD4225C4AB579A3578A92B290D5C49052F762BCA72FBA
- Notes: this is an unsigned release APK for verification.

## Highlights
- Enforced onboarding order for key setup actions.
- Add message share/copy and photo upload support.
- Add voice STT and TTS support.
- Demo ready:
  - Model provider: Deepseek/Qwen (1M demo token).
  - Search provider: Aliyun OpenSearch.
  - Voice: Domestic Tencent ASR.


## Shizuku Setup (Added)
### 1) Download Shizuku
Pick either source:
- Official site: https://shizuku.rikka.app/download/
- GitHub releases: https://github.com/RikkaApps/Shizuku/releases

Install and open Shizuku on your phone.

### 2) Pair and Start via Wi-Fi (No USB-root required after setup)
Prerequisites:
- Phone and computer are on the same Wi-Fi network.
- Developer options enabled on phone.
- Wireless debugging enabled on phone.

Steps:
1. In Shizuku, choose Start via wireless debugging.
2. Tap Pairing in Android wireless debugging panel and note pairing code and port.
3. On PC, run:
   - adb pair <phone_ip>:<pairing_port>
   - Enter pairing code when prompted.
4. Then connect debug channel:
   - adb connect <phone_ip>:<adb_port>
5. Return to Shizuku and tap Start (or authorize if prompted) until status is running.

Example (replace placeholders):
- adb pair 192.168.1.23:37123
- adb connect 192.168.1.23:41739

### 3) Configure Shizuku in pocopaw
In pocopaw:
1. Open Settings.
2. Locate Shizuku bootstrap section.
3. Verify Shizuku status is available/running.
4. Tap Prepare with Shizuku.
5. Grant Shizuku API permission when prompted and verify prerequisites complete.
6. Enable Auto prepare on startup if you want startup auto-bootstrap.
7. Confirm top status row updates (for example, Shizuku status and related readiness).

## Screenshots (User-Provided)
- Screenshot 1: Shizuku main page showing wireless flow and running status.
  - Key actions highlighted: Pairing -> Start (via Wireless debugging).
- Screenshot 2: pocopaw Settings page Shizuku bootstrap section.
  - Key actions highlighted: Prepare with Shizuku and Auto prepare on startup switch.

## Upgrade Notes
- Existing installs may require one clean launch after update to refresh onboarding state.
- If onboarding/testing state is inconsistent, clear app data and relaunch.

## QA Checklist
- [ ] First launch defaults to Settings page.
- [ ] Onboarding buttons highlight in order:
  1. one-off tool discovery
  2. accessibility settings
  3. screen capture
- [ ] After all three steps are completed, UI auto-switches to chat.
- [ ] Second launch enters chat directly.
- [ ] Shizuku setup path works end-to-end with Wi-Fi pairing.


