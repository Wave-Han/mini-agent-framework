# Design: Streaming + Token Window Trimming

> Date: 2026-11-21 · Status: approved (conversation) · Scope: small change

## Intent & Why

Close the two limitations listed in the README's honest-limitations section:
`OllamaClient` blocks up to 5 min with `stream:false`, and `AgentSession.buildContext()`
returns the full root→leaf path with no budget. Purpose: turn "I know what's missing"
into "I already fixed it" for interviews.

## Goals

1. Streaming: `chatStream(model, messages, tools, Consumer<String> onDelta)` returning
   an assembled final-response JSON **in the same shape as the non-streaming response**,
   so `LlmAgentLoop`/`ToolCallParser` stay unchanged. Content deltas invoke the callback
   live; `tool_calls` fragments are buffered silently until `done:true`.
2. Trimming: `buildContext(int maxTokens, TokenEstimator)` overload (existing
   `buildContext()` untouched → current tests keep passing). Policy: keep system
   messages, fill from newest backwards, drop the middle, one `[... N trimmed ...]`
   placeholder, assistant-tool_calls + its tool_result always kept as a pair.
3. All tests offline (canned NDJSON samples, synthetic long sessions) — same strategy
   as the existing suite; no test depends on a live Ollama.

## Non-goals

- Middle summarization (needs extra LLM call; YAGNI)
- Real tokenizer dependency (jtokkit) — pluggable interface instead
- Unifying the ModelRegistry / OllamaClient provider paths (separate limitation)
- Changing the HTTP client library

## Constraints

- Java 17, existing deps only (Jackson already present)
- `chat()` stays as fallback; loop gets a streaming switch
- Hand-rolled request-body JSON in `OllamaClient` is replaced with Jackson while we
  touch the file (parsing side was already migrated on Day 3; request side was not)

## Approaches considered

- Streaming: (a) expose raw chunk stream to callers — rejected: pushes NDJSON and
  fragment-assembly complexity into every consumer; (b) callback + assembled final
  response — chosen: single place handles the hard part (tool_calls buffering),
  consumers stay simple. (c) internal-only streaming without callback — rejected:
  loses the demo/typewriter value with no real saving.
- Trimming: (a) sliding window without head preservation — rejected: loses system
  prompt, unacceptable for an Agent; (b) head+tail with pair integrity — chosen;
  (c) summarize dropped middle — rejected (non-goal above).

## Decisions (mine, correctable)

- Token estimation: `TokenEstimator` interface + `HeuristicTokenEstimator`
  (chars/4), swappable for jtokkit later
- Pair integrity rule: if the budget boundary falls between an assistant
  tool_calls entry and its tool_result, extend to keep the pair
- Placeholder entry is a plain session-shaped entry, not persisted to the tree

## Left to the builder

- Exact NDJSON chunk grammar coverage (content delta / tool_calls delta / done stats)
- Whether streaming is default-on in `LlmAgentLoop` config or opt-in (prefer: opt-in
  constructor flag, default off, so existing demos behave identically)
