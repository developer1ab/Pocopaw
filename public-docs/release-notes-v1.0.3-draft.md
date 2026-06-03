# pocopaw v1.0.3 Release Notes

## Release Artifact
- Version Name: 1.0.3
- Version Code: 4
- Variant: release
- File: app/build/outputs/apk/release/app-release-unsigned.apk
- SHA256: E996AEDAC2422CB0ADC6984669D245483C0CE3F258C4D970F7F0413690107CDA
- Notes: this is an unsigned release APK for verification.

## Highlights
- **Assistant Overlay**: new global voice assistant bubble with voice input + speech playback status + rounded animated panda video.
- **Five-step Onboarding**: guided setup flow (tool discovery → accessibility → screen capture → download Shizuku → prepare with Shizuku) with smart skip for completed steps.
- **Long-text TTS support**: cloud TTS now auto-splits long text into ≤150 char chunks and streams playback segment by segment.
- **Startup permission chain**: microphone + notification + contacts permissions requested in sequence at first launch.
- **Overlay lifecycle**: app icon restores main UI and auto-closes overlay bubble, overlay button restores app and minimizes it to background.
- **Prompt improvement**: pocopaw now introduces itself as a silly tinkering panda with app-control tricks (disclaimer: chaotic tinkerer, not responsible for mishaps).
- **Chinese localization**: overlay UI and on-screen controls fully localized.

## Full Changelog
- Added `AssistantOverlayController` with rounded video bubble (TextureView + MediaPlayer)
- Added `AssistantOverlayService` foreground service with runtime state flag
- Added `DemoOnboardingStep` 5-step flow with auto-highlight and smart skip
- Added Shizuku download button + setup guide text (EN/CN)
- Added `TencentTtsClient.splitMp3Chunks()` for long text splitting
- Added streaming cloud TTS playback (`playNextCloudTtsChunk()`)
- Added chained startup permissions (contacts → mic → notification)
- Added `AssistantOverlayService.isRunning` flag for lifecycle management
- Added overlay auto-close on `onResume` when user returns to main app
- Updated `buildAssistantIdentityInstruction()` with playful panda persona
- Updated `buildShizukuSurfaceState()` to prioritize live snapshot over persisted status
- Fixed accessibility onboarding flicker after grant
- Fixed Shizuku status showing "prepared finished" after uninstall
- Fixed VideoView rounded corners (replaced with TextureView workaround documented in design doc §4.6)