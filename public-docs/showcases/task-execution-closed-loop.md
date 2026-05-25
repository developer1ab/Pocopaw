# Overall Flow

## Summary

Demonstrates an end-to-end path from user request to local execution writeback.

## Demo Video

Video: `overall.mp4`

## Scenario

- User goal: Complete one practical phone task through the agent flow.
- Input: A natural-language task request in conversation.
- Expected outcome: Execution completes and result is written back to conversation.

## Preconditions

- App version: fill in before publishing
- Device model and OS: fill in before publishing
- Network condition: stable
- Permission/readiness state: accessibility, capture, and related readiness items completed

## Steps

1. Enter the task request in the conversation view.
2. Confirm the execution boundary when prompted.
3. Observe runtime progress and wait for completion.
4. Verify the writeback appears in conversation.

## Result

- Final app behavior: task result and completion summary are visible to the user.
- Time to completion: fill in measured value
- Retry count (if any): fill in measured value

## Reproduce Locally

1. Follow [Install and configure](../install-and-configure.md).
2. Prepare readiness surfaces in app settings.
3. Run the same request and compare result shape.

## Notes

- Replace placeholder details with measured values before wide promotion.