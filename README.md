<p align="center">
	<img src="public-docs/assets/pocopaw-banner.png" alt="pocopaw banner" width="100%" />
</p>

# pocopaw

Android personal execution agent.

pocopaw is an Android-based personal execution agent prototype. It is not trying to be another chat-first assistant. The goal is to move a user request through a controlled local chain: understanding, preparation, execution, verification, answer, and later reuse.

The current product focus is deliberately narrow. Instead of exposing every capability at once, pocopaw is focused on making the local execution chain stable, observable, and controllable first.

Quick links: [Docs](public-docs/README.md) · [Wiki](https://github.com/developer1ab/Pocopaw/wiki) · [Showcases](public-docs/showcases/README.md) · [Install](public-docs/install-and-configure.md) · [Main design](public-docs/main-design.md) · [Roadmap](public-docs/roadmap.md) · [License](LICENSE)

## Watch 3 Demo Flows

Start here for the fastest product impression.

<table>
	<tr>
		<td width="33%" valign="top">
			<strong>Overall Flow</strong><br/>
			End-to-end path from user request to execution writeback.<br/><br/>
			<a href="public-docs/showcases/overalls.mp4">Watch demo</a><br/>
			<a href="public-docs/showcases/task-execution-closed-loop.md">Read walkthrough</a>
		</td>
		<td width="33%" valign="top">
			<strong>Shopping Flow</strong><br/>
			Shopping-oriented request handling with visible execution feedback.<br/><br/>
			<a href="public-docs/showcases/shoppings.mp4">Watch demo</a><br/>
			<a href="public-docs/showcases/shopping-flow.md">Read walkthrough</a>
		</td>
		<td width="33%" valign="top">
			<strong>Bluetooth Flow</strong><br/>
			Bluetooth task execution with completion and conversation writeback.<br/><br/>
			<a href="public-docs/showcases/bluetooths.mp4">Watch demo</a><br/>
			<a href="public-docs/showcases/bluetooth-flow.md">Read walkthrough</a>
		</td>
	</tr>
</table>

All showcase pages: [Showcase Hub](public-docs/showcases/README.md)

## What pocopaw is

pocopaw is a local-first Android execution agent for personal workflows.

It is trying to solve questions like these:

- How should the system keep up with a request while the user is still clarifying it?
- When should the product answer directly, when should it prepare a plan, and when is it finally allowed to execute?
- How should execution prerequisites, missing details, and risk boundaries be made visible before execution starts?
- How should results, evidence, preferences, and reusable process knowledge be written back after a task finishes?

In product terms, pocopaw is closer to a personal execution agent on a phone than to a general-purpose chat application with a few automation features attached.

## What it is not

- It is not a black-box agent that silently pushes every request into execution.
- It is not a single control plane where search, memory, preference, and execution authority are mixed together.
- It is not a demo prototype held together by implicit fallback behavior.

The project puts more weight on clear boundaries between user intent, planning, execution authority, runtime behavior, and later reuse.

## Product surface

The current product surface has three visible areas:

- A unified conversation surface where the user can ask, refine, discuss, and request actions.
- An execution observation surface where the product shows boundaries, progress, and writeback once a task moves forward.
- A settings and diagnostics surface for model configuration, readiness checks, permission state, and Shizuku preparation.

These are not isolated mini-tools. They are different views over the same local execution chain.

## Architecture at a glance

### 1. One conversation surface

Ordinary chat, reasoning-heavy turns, search-assisted turns, and task requests all stay in one conversation channel. The product is meant to feel like one continuous agent, not a bundle of disconnected modes.

### 2. Meaning first, then stage transition

Not every user message should become an execution task. pocopaw first stabilizes the current semantic state, then decides whether the system should keep accumulating context, enter preparation, or enter execution.

### 3. Execution must cross a clear boundary

Execution does not start from raw natural language. Before execution begins, pocopaw narrows the request into a local execution boundary: objective, missing information, risk boundary, and start conditions.

### 4. Enhancement layers do not replace execution authority

Search, memory, preference, process reuse, and proactivity all matter, but they remain enhancement layers. They can improve the system. They do not get to replace execution authority.

### 5. Local-first controllability

The project prioritizes an Android-local execution path that can be observed, verified, and written back. That is why readiness, permission state, screen capture, accessibility, and process evidence are treated as product-level concerns instead of hidden plumbing.

## Current focus

The project is still centered on Phase 1: Foundation.

Current priorities are:

- Stabilize the path from user input into preparation and execution prerequisites.
- Converge the basic readiness surface around Shizuku, accessibility, and screen capture.
- Keep the interaction style low-friction and low-interruption.
- Establish an explainable search-assisted answer chain.
- Make common requests reliably enter the correct answer, planning, or execution path.

## Roadmap

### Phase 1: Foundation

Make the base chain stable enough for common requests to enter answering, planning, or execution preparation without getting stuck in avoidable friction.

### Phase 2: Experience Upgrade

Make the product faster, smoother, and easier to take over. This phase emphasizes natural interaction, faster control loops, and kiosk-style handoff or takeover surfaces.

### Phase 3: Intelligence Upgrade and Search Refinement

Build stronger prediction, memory, preference, and process reuse capabilities on top of a stable evidence plane, then introduce more timely recommendation and proactive behavior.

### Phase 4: Multi-task Orchestration

Expand the product from a single-task execution agent into one that can decompose, orchestrate, and re-plan more complex task collections.

## Public documentation

- [Public docs index](public-docs/README.md)
- [Install and configure](public-docs/install-and-configure.md)
- [Main design](public-docs/main-design.md)
- [Roadmap](public-docs/roadmap.md)
- [User interaction design](public-docs/user-interaction-design.md)
- [Execution runtime design](public-docs/execution-runtime-design.md)
- [Safety boundary design](public-docs/safety-boundary-design.md)

## License

This repository is licensed under the Business Source License 1.1 (`BUSL-1.1`).

In practical terms:

- individual developers can use the project for learning, research, evaluation, and personal projects under the license terms;
- the Additional Use Grant also permits individual personal production use when the user is not acting for or on behalf of an organization;
- production use by or for an organization, and commercial reuse of the Licensed Work as a product or hosted service, requires a separate commercial license from the Licensor.

See [LICENSE](LICENSE) for the controlling terms.
