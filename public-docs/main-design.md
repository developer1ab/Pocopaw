# pocopaw Main Design

This is the public, English-facing copy of the top-level design document.

## 1. Guiding principle

The passive chain is always driven by the user's latest explicit intent.

- Stage signals describe interpretation and local state.
- Stage signals do not own autonomous execution authority.
- Active or silent topics own passive topic authority.
- Live execution authority is carried by `TaskRecord -> TaskExecutionBoundaryPacket -> PreparedExecutionStart`.

## 2. Top-level architecture

### 2.1 Single-shell application

pocopaw remains a single-activity Android shell application. The main product surface brings conversation, execution observation, and settings into one place.

### 2.2 Prompt and contract ownership

`PromptCenter` is the sole owner of prompt structure and packet contracts. UI, runtime, and bridges consume its output; they do not invent parallel prompt surfaces.

### 2.3 Passive topic authority vs live execution authority

Passive conversation state is managed through active or silent topics and topic detail slots.

Live execution only begins after an explicit execution request is narrowed into a local execution authority object. That separation is deliberate: not every conversation state transition is allowed to become execution.

### 2.4 Local-first execution runtime

The execution runtime is a unified screenshot-first shell. Reusable process assets can guide execution, but they do not override the main local execution authority.

### 2.5 Evidence plane

Memory, preference, personalization, proactivity, and process learning share the same evidence plane, but none of them are allowed to replace execution authority.

### 2.6 Explicit provider profiles

Provider configuration is modeled through explicit semantic, vision, and search profiles. Implicit fallback is intentionally avoided.

## 3. Layering

The product is split into four broad layers:

- Shell and interaction: visible surfaces and entry points.
- Semantic and prompt governance: packets, contracts, and semantic turn orchestration.
- Topic and task boundary: where user meaning is narrowed into local authority.
- Execution and learning: runtime, writeback, memory, process reuse, and learning.

## 4. End-to-end flow

At a high level, the current flow is:

1. The user sends a message into the unified conversation surface.
2. The semantic turn is built locally, with optional search-assisted enrichment when needed.
3. Passive topic state is updated first.
4. A planning request may produce a local task draft and a natural-language plan.
5. An execution request may produce a local task record and execution boundary.
6. Execution starts only after the boundary is explicit and visible.
7. Runtime output is written back into conversation, execution observation, and later evidence or reuse flows.

## 5. Current public module entry points

The public documentation surface keeps these entry points:

- `main-design.md`
- `roadmap.md`
- `user-interaction-design.md`
- `execution-runtime-design.md`
- `safety-boundary-design.md`

The rest of the internal module breakdown remains in the local working documentation set.
