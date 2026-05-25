# Safety Boundary Design

This is the public English copy of the safety boundary module summary.

## 1. Module role

The safety boundary module provides outer-layer safety decisions before prompt construction or proactive delivery proceeds further.

Its job is to:

- produce explicit safety decisions,
- downgrade or confirm higher-risk capabilities,
- and surface boundary risk early instead of leaving it buried inside runtime failure.

It does not own final route selection or runtime orchestration.

## 2. Decision surfaces

The current public safety surface covers three main decision areas:

### 2.1 Proactive lane

When the workflow is proactive and tolerance is low, execution-shaped proactive signals should be downgraded into hint-only delivery or explicit confirmation.

### 2.2 Tool risk

Restricted tools require confirmation. Sensitive tools require at least an advisory step. Lower-risk tools can continue to readiness evaluation.

### 2.3 Execution readiness

The safety layer provides a decision. It does not take over execution authority. Even when a task is ready to start, safety can only advise or require confirmation; it cannot start execution by itself.

## 3. Core rules

The current rules are:

1. Stage labels and stage recommendations are not execution permission.
2. Without an explicit `START_PREPARING`, `START_EXECUTING`, or an approved proactive request, downstream layers must not treat state as an automatic trigger.
3. Communication-style system intents must satisfy local recipient, permission, and unique-contact preconditions before launch.
4. Missing parameters, missing permission, zero matches, or multiple matches must fail explicitly.
5. Shizuku setup must be user-initiated or explicitly enabled by the user for auto-prepare.
6. Shizuku readiness is only readiness preparation; it is not business execution authority.

## 4. Design constraints

- Safety must remain explicit at the outer layer.
- New high-risk capabilities must update the risk matrix and safety rules together.
- Proactive chains must pass the safety gate before they become visible requests or visible actions.
- High-risk execution requests still require visible confirmation or advisory behavior even when they come directly from the user.

## 5. Current direction

The public-facing next steps are:

- finer-grained confirmation matrices,
- more route-level safety policy coverage,
- and clearer user-facing mapping for blocked, pending, or unsupported Shizuku states.
