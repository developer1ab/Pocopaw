# User Interaction Design

This is the public English copy of the user interaction module summary.

## 1. Surface structure

The interaction layer is built around a single `MainActivity` shell.

The current visible surfaces are:

- a console surface,
- a settings surface,
- and inside the console, conversation and execution-focused views.

The conversation view is the unified chat surface. Ordinary chat, reasoning-heavy chat, search-assisted chat, and task requests are all presented inside the same conversation experience.

## 2. Conversation contract

The conversation surface follows a few fixed rules:

1. A user message enters the conversation immediately.
2. A pending assistant turn is created immediately and filled progressively.
3. Reasoning, search detail, and the final answer stay attached to the same assistant turn.
4. Search is a semantic or planning-time capability, not a permission for the runtime to keep searching after execution starts.
5. Conversation state alone must not silently start planning or execution.

The UI explicitly distinguishes:

- the current state,
- the user's request intent,
- and the recommended next stage.

Those are not interchangeable.

## 3. Main interaction flow

The current flow is:

1. Read the composer text.
2. Append the user turn.
3. Build the turn configuration from settings and per-turn capability switches.
4. Stream the assistant turn.
5. Persist semantic results into local topic state.
6. Show a plan directly when the request is a planning request.
7. Only show execution recap and enter runtime when the request is an execution request and local validation passes.

## 4. Settings and diagnostics

The settings surface is responsible for:

- provider profile and model-facing controls,
- readiness entry points,
- one-off diagnostics,
- and Shizuku preparation status.

Shizuku is treated as readiness preparation only. It does not create a separate business execution path.

## 5. Design constraints

- The UI consumes store output; it does not invent execution authority.
- Prompt wording and contract shape belong to prompt governance, not to the UI layer.
- Pending UI state must not replace the formal local store state.
- Auto-start behavior is only allowed after a real execution request and a validated local boundary.
