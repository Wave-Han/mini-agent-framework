# Mini Agent Framework

A minimal Java 17 AI Agent framework ported from the [pi-mono](https://github.com/badlogic/pi-mono)
architecture, built to understand what frameworks like Spring AI and LangChain4j
actually do under the hood — Spec, Harness, and the tool-calling loop, with zero
framework magic.

> This is a learning artifact, deliberately hand-rolled: the LLM provider client
> is plain `java.net.http.HttpClient`, the loop is a plain `for` loop. Every
> layer is small enough to read in one sitting.

## Architecture

```
com.example.agent
├── spec/          Spec layer — what you DECLARE
│   ├── AgentTool      tool contract: name/description/schema (metadata for the LLM) + execute
│   └── ToolResult     immutable result record; isError/terminate stay data, never exceptions
├── harness/       Harness layer — what the framework RUNS
│   └── AgentLoop      tool execution engine (the "DispatcherServlet of agents"):
│                      registry lookup -> prepare -> execute -> finalize, seq/parallel dispatch
├── interceptor/   Guardrails
│   ├── ToolInterceptor beforeToolCall (block) / afterToolCall (override), Spring-HandlerInterceptor style
│   └── AuditInterceptor minimal observer implementation
├── session/       Memory
│   └── AgentSession   append-only message TREE: branch() = git checkout,
│                      buildContext() = the path from leaf to root
├── llm/           LLM layer
│   ├── LlmAgentLoop   the real while loop: send -> parse -> execute tools -> append -> repeat,
│                      with three exit conditions (plain text / tool terminate / MAX_ITERATIONS)
│   ├── ToolCallParser Jackson-backed response parsing (see Testing)
│   └── OllamaClient   hand-rolled provider, plain HttpClient -> POST /api/chat
├── model/         Multi-provider registry
│   └── ModelRegistry  ConcurrentHashMap registry + default provider (DriverManager-style)
└── tools/         Example AgentTool implementations (calculator, current time)
```

**The loop in one paragraph**: the tool list (name + description) and the user
message are sent to the LLM; the LLM — not any `if/else` in this codebase —
decides whether to answer directly or emit `tool_calls`; executed tool results
are appended back into the message history and the whole history is sent again,
until the model returns plain text or `MAX_ITERATIONS` trips. Java is the
executor and message keeper; the LLM is the router.

## Quick Start

Requires Java 17 and Maven.

```bash
mvn package
java -jar target/mini-agent-framework-1.0.0.jar     # MiniAgentDemo: runs with NO api key
```

`MiniAgentDemo` exercises the full Spec → Session → Loop → Interceptor → Tool
pipeline locally. For a real LLM round trip, run `LlmAgentDemo` with a local
[Ollama](https://ollama.com) server (`ollama pull qwen2.5:7b`).

## Testing

```bash
mvn test    # 18 tests, green
```

The suite pins the behaviors that matter:

- **`ToolCallParserTest`** — regression tests born from a real bug: the original
  hand-rolled `split(",")` parser truncated argument values containing commas
  (`"1+2,3*4"` → `"1+2"`) and flattened nested object arguments to strings. The
  failing tests were written first (RED), then the parser was rewritten with
  Jackson (GREEN) and 125 lines of hand-rolled parsing were deleted.
- **`AgentSessionTest`** — branching rewinds the leaf; context follows the new
  path only; abandoned branches are never lost.
- **`AgentLoopTest`** — a blocked interceptor's reason, an unknown tool and a
  throwing tool all come back as `ToolResult.error` instead of blowing up the loop.
- **`ModelRegistryTest`** — registry contract (first registration wins default).

## Design Decisions

| Decision | Rationale |
|---|---|
| Errors are data (`ToolResult.error`), never exceptions | the LLM must SEE failures to self-correct; an exception would hide the failure from the model |
| Session is an append-only tree, not a list | `branch()` enables "regenerate from this point" and A/B-ing two prompts over an identical history prefix |
| Tools declare `executionMode` | side-effecting tools opt out of parallel execution declaratively |
| `MAX_ITERATIONS` circuit breaker | probabilistic loops need a hard stop against runaway cost |
| Hand-rolled `OllamaClient` | understanding `ChatModel` abstractions requires having felt the pain they remove |

## Known Limitations (honest list)

- `AgentTool.parameterSchema()` is not yet wired into the tool definitions sent
  to the LLM (`properties` is emitted empty).
- `AgentSession.buildContext()` has no token-window trimming; long conversations
  will outgrow the context window.
- `AgentLoop` uses an unbounded cached thread pool.
- `ModelRegistry` (Spring AI `ChatModel`) and `OllamaClient` are two provider
  paths that are not yet unified.

## Roadmap

- [ ] Wire `parameterSchema()` into `buildToolDefinitions()`
- [ ] Token-aware context trimming in `AgentSession`
- [ ] Bounded executor + timeouts in `AgentLoop`
- [ ] Unify providers behind one `ChatModel` path

## License

MIT.
