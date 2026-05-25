# pocopaw Roadmap 2026-05-19

## 1. Boundary

This document only keeps the product phases, their goals, and their order. It does not expand into implementation-level detail.

The project currently uses four phases:

1. Phase 1: Foundation
2. Phase 2: Experience Upgrade
3. Phase 3: Intelligence Upgrade and Search Refinement
4. Phase 4: Multi-task Orchestration

## 2. North star

pocopaw is intended to become an Android personal execution agent. A user expresses a goal through natural language or another input form, and the system should complete understanding, preparation, execution, verification, answering, and long-term reuse with as little unnecessary interruption as possible.

The overall order is fixed:

foundation first, then better interaction, then stronger initiative, then more complex task orchestration.

## 3. Phase roadmap

### 3.1 Phase 1: Foundation

Goal: make pocopaw a stable, usable base product.

Focus:

1. Stabilize the path from input to execution preparation.
2. Converge the basic readiness surface around Shizuku, accessibility, and screen capture.
3. Establish a low-clarification interaction style so common requests can directly enter answer, planning, or execution preparation.
4. Establish an explainable search-assisted answer chain.

Outcome:

Common user requests can enter the answer, planning, or execution path much more reliably, without getting stuck in avoidable preparation friction.

### 3.2 Phase 2: Experience Upgrade

Goal: make pocopaw smoother, faster, and easier to take over.

Focus:

1. Add more natural interaction entry points such as voice.
2. Improve speed-of-control mechanisms, including history-assisted faster operation.
3. Build floating self-service, kiosk, and takeover experiences that expose the main user entry points directly on device surfaces.

Outcome:

The product becomes easier to operate, more natural to interact with, and easier to take over when needed.

### 3.3 Phase 3: Intelligence Upgrade and Search Refinement

Goal: make pocopaw better at accumulation, reuse, anticipation, and finer-grained action.

Focus:

1. Strengthen the memory, preference, and process evidence plane.
2. Build prediction around target objects, tool choice, and process flow choice.
3. Introduce better timing for proactive recommendation and execution with personalization bias applied to the proactive path.

Outcome:

The system becomes progressively better at timing, anticipation, and proactive behavior after enough evidence has accumulated.

### 3.4 Phase 4: Multi-task Orchestration

Goal: evolve pocopaw from a single-task execution agent into a planner, orchestrator, and executor for more complex task sets.

Focus:

1. Decompose one goal into a structured task collection.
2. Execute task collections under orchestration and re-plan between nodes when the business state changes.
3. Introduce more strategy-shaped exploratory flows from abstraction to orchestration.

Outcome:

The system can begin to take on more complex planning, orchestration, and execution, and adjust its path while moving toward the intended outcome.

## 4. Cross-cutting principles

All phases must preserve these rules:

1. The passive chain is always driven by the user's latest explicit meaning.
2. UI, prompt governance, task boundary, and runtime authority layers must stay distinct.
3. Search, memory, preference, reuse, and proactivity remain evidence or strategy layers; they do not replace execution authority.
4. High-risk actions require explicit confirmation and visible safety boundaries.
5. Device-facing capabilities should be validated across Samsung, OPPO, and multiple readiness states.

## 5. Current rollout order

1. Tighten the scope and acceptance boundary of Phase 1 first.
2. After Phase 1 is stable, move into the experience and takeover work of Phase 2.
3. Phase 3 depends on a stable evidence plane and process reuse foundation.
4. Phase 4 depends on a stable search contract and clear multi-model boundaries.
