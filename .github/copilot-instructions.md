# Repository Custom Instructions

This workspace is the Android popopaw app. Keep guidance minimal and anchored to the app under `app/`.

## Project Facts

- Kotlin Android app with Gradle; primary module is `app`
- Main architecture anchors: `MainActivity.kt`, `PrototypeStore.kt`, `PrototypeModels.kt`, `PromptCenter.kt`, and `process/`
- Current behavior baseline lives in [../docs/design11.md](../docs/design11.md)

## Working Rules

- Preserve shell boundaries unless the task explicitly restructures them
- Keep prompt and packet contract changes in `PromptCenter`
- Do not hardcode secrets, and never add, preserve, restore, or execute any fallback mechanism without explicit user approval in design, implementation, or debug work; silent runtime fallback behavior is always forbidden.
- Keep execution architecture consistent across domains: prefer one generalized mechanism that applies to all domains, or a clearly separated per-domain subsystem; do not mix ad hoc domain-specific behavior into an otherwise shared path.
- When work is driven by a design document or review feedback, implement and modify strictly against that controlling input, and keep all execution fully centered on it.
- Next steps and handoff may come only from explicitly unimplemented parts of the approved design or from explicit review comments; do not infer new tasks, recommend unrelated follow-up work, or expand into adjacent cleanup on your own.

## Validation

- Run the narrowest affected unit test first with `.\gradlew :app:testDebugUnitTest --tests "..."`
- Run `.\gradlew :app:testDebugUnitTest` for broader Kotlin logic changes
- Run `.\gradlew :app:assembleDebug` for Gradle, manifest, resource, or UI-surface changes
