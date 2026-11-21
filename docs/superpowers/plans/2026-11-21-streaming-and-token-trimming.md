# Streaming + Token Window Trimming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give mini-agent-framework NDJSON streaming (with live content callback, buffered tool_calls) and head+tail token-window trimming on `AgentSession.buildContext`.

**Architecture:** A pure, stateful `OllamaStreamParser` turns NDJSON lines into the framework's canonical flat response shape (`{"content":...,"tool_calls":[...]}`) so `LlmAgentLoop`/`ToolCallParser` stay unchanged. A pure `ContextTrimmer` implements keep-head/keep-tail/pair-atomic trimming, exposed via a `buildContext(int, TokenEstimator)` overload; the old `buildContext()` delegates with unlimited budget.

**Tech Stack:** Java 17, Jackson (existing), JUnit 5 + AssertJ via spring-boot-starter-test (existing). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-21-streaming-and-token-trimming-design.md`

## Global Constraints

- Java 17; no new dependencies (Jackson and spring-boot-starter-test already in pom.xml)
- Existing tests must keep passing: `buildContext()` signature/behavior unchanged
- All new tests offline — no test touches a live Ollama server
- Error-as-data: malformed stream lines are skipped, never thrown (matches `ToolCallParser` philosophy)
- If `mvn` is not on PATH, use `~/.m2/maven-wrapper/apache-maven-3.9.9/bin/mvn.cmd` (Day-1 finding)
- Conventional commits (`feat:`/`test:`/`docs:`), no attribution lines

## Review Focus

1. Content split across many chunks → one callback per chunk, assembled full text at end (Task 1 pins)
2. tool_calls arguments fragmented as JSON-string pieces across chunks → merged then parsed once (Task 2 pins)
3. Blank or malformed NDJSON line mid-stream → skipped, stream continues, no exception (Task 2 pins)
4. Trim boundary falls between an entry and its tool_result → pair stays atomic, no orphan tool_result (Task 5 pins)
5. Budget smaller than system + newest entry → system never dropped, newest always present, result never empty (Task 5 pins)
6. Quotes/newlines inside message content survive request-body building (the old string-concat builder would corrupt them) (Task 3 pins)

---

### Task 1: OllamaStreamParser — content accumulation

**Files:**
- Create: `src/main/java/com/example/agent/llm/OllamaStreamParser.java`
- Test: `src/test/java/com/example/agent/llm/OllamaStreamParserTest.java`

**Interfaces:**
- Produces: `public final class OllamaStreamParser` with
  `public void accept(String line, java.util.function.Consumer<String> onDelta)` (one NDJSON line; `onDelta` nullable),
  `public boolean isDone()`,
  `public String toResponseJson()` → `{"content":"<assembled>"}` plus `"tool_calls":[...]` only when calls exist. Content delta arrives when a line has `message.content` non-empty; `done:true` sets done.

- [ ] **Step 1: Write the failing test**

```java
@Test
void contentChunks_eachEmitDelta_andAssembleFullText() {
    var parser = new OllamaStreamParser();
    List<String> deltas = new ArrayList<>();
    parser.accept("{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"done\":false}", deltas::add);
    parser.accept("{\"message\":{\"role\":\"assistant\",\"content\":\",世界\"},\"done\":false}", deltas::add);
    parser.accept("{\"done\":true,\"total_duration\":1}", null);
    assertThat(deltas).containsExactly("你好", ",世界");
    assertThat(parser.isDone()).isTrue();
    assertThat(ToolCallParser.extractContent(parser.toResponseJson())).isEqualTo("你好,世界");
    assertThat(ToolCallParser.parseToolCalls(parser.toResponseJson())).isEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OllamaStreamParserTest`
Expected: COMPILATION ERROR (`OllamaStreamParser` does not exist)

- [ ] **Step 3: Implement `OllamaStreamParser` (content path only)**

Jackson `ObjectMapper.readTree` per line; guard `line == null || isBlank` → return. Accumulate `content` into a `StringBuilder`; emit delta via `onDelta` when non-null. `toResponseJson()` builds an `ObjectNode` with `content` (+ nothing else yet) via the same `ObjectMapper`, returns `toString()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=OllamaStreamParserTest`
Expected: PASS

- [ ] **Step 5: Commit**

`git add src/main/java/com/example/agent/llm/OllamaStreamParser.java src/test/java/com/example/agent/llm/OllamaStreamParserTest.java && git commit -m "feat: stream parser accumulates content chunks with live delta callback"`

---

### Task 2: OllamaStreamParser — tool_calls buffering + malformed-line tolerance

**Files:**
- Modify: `src/main/java/com/example/agent/llm/OllamaStreamParser.java`
- Test: `src/test/java/com/example/agent/llm/OllamaStreamParserTest.java` (add tests)

**Interfaces:**
- Consumes: Task 1's class.
- Produces: tool_calls support in `accept`/`toResponseJson`. Grammar per line: `message.tool_calls[i]` may carry `function.name` (first chunk for that index) and `function.arguments` as **string fragments** (append) or as a complete object (merge). `toResponseJson()` emits `"tool_calls":[{"function":{"name":...,"arguments":{...}}}]` — arguments = the accumulated fragment string parsed via Jackson into an object; unparseable fragments become an empty map (error stays data). Malformed JSON line → skipped silently.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void toolCallArgumentsFragmentedAcrossChunks_mergedAndParsedAtDone() {
    var parser = new OllamaStreamParser();
    List<String> deltas = new ArrayList<>();
    parser.accept("{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expr\\\":\"}}]},\"done\":false}", deltas::add);
    parser.accept("{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"\\\"1+2\\\"}\"}}]},\"done\":false}", deltas::add);
    parser.accept("{\"done\":true}", null);
    assertThat(deltas).isEmpty(); // tool fragments never surface as content deltas
    var calls = ToolCallParser.parseToolCalls(parser.toResponseJson());
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).get("function"))
        .extracting(f -> ((Map<?,?>)f).get("name"), f -> ((Map<?,?>)f).get("arguments"))
        .containsExactly("calculator", Map.of("expr", "1+2"));
}

@Test
void malformedLine_isSkipped_streamContinues() {
    var parser = new OllamaStreamParser();
    List<String> deltas = new ArrayList<>();
    parser.accept("not json at all", deltas::add);
    parser.accept("", deltas::add);
    parser.accept("{\"message\":{\"content\":\"ok\"},\"done\":false}", deltas::add);
    parser.accept("{\"done\":true}", null);
    assertThat(deltas).containsExactly("ok");
    assertThat(ToolCallParser.extractContent(parser.toResponseJson())).isEqualTo("ok");
}

@Test
void argumentsAsCompleteObject_isMerged() {
    var parser = new OllamaStreamParser();
    parser.accept("{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"now\",\"arguments\":{\"tz\":\"UTC\"}}}]},\"done\":false}", null);
    parser.accept("{\"done\":true}", null);
    var calls = ToolCallParser.parseToolCalls(parser.toResponseJson());
    assertThat(calls.get(0).get("function")).isInstanceOf(Map.class)
        .satisfies(f -> assertThat(((Map<?,?>)f).get("arguments")).isEqualTo(Map.of("tz","UTC")));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=OllamaStreamParserTest`
Expected: new tests FAIL (tool_calls ignored → `calls` empty / assertion errors), Task 1 test still passes

- [ ] **Step 3: Implement tool_calls accumulation**

Per line: if `message.tool_calls` array present, for each element take `index` (default running position) and read `function.name` (store once) and `function.arguments` — `isTextual()` → append fragment to that index's `StringBuilder`; otherwise (object) → serialize node to string and append. At `toResponseJson()`: for each index, parse accumulated string with Jackson into `Map`; on parse failure use `Map.of()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=OllamaStreamParserTest`
Expected: all PASS

- [ ] **Step 5: Commit**

`git add -A src/main/java/com/example/agent/llm/OllamaStreamParser.java src/test/java/com/example/agent/llm/OllamaStreamParserTest.java && git commit -m "feat: buffer fragmented tool_calls across stream chunks, skip malformed lines"`

---

### Task 3: OllamaClient — Jackson request body + chatStream

**Files:**
- Modify: `src/main/java/com/example/agent/llm/OllamaClient.java` (replace hand-rolled body builder for both `chat` and `chatStream`)
- Test: `src/test/java/com/example/agent/llm/OllamaClientTest.java` (create)

**Interfaces:**
- Consumes: `OllamaStreamParser.accept/isDone/toResponseJson` (Task 1/2).
- Produces:
  - `static String buildRequestBody(String model, List<Map<String,Object>> messages, String toolsJson, boolean stream)` (package-private, Jackson-based)
  - `public String chatStream(String model, List<Map<String,Object>> messages, String toolsJson, java.util.function.Consumer<String> onDelta)` — POSTs with `stream:true`, reads lines via `HttpResponse.BodyHandlers.ofLines()`, feeds `OllamaStreamParser`, returns `toResponseJson()`. Non-200 → same RuntimeException style as `chat`.
  - `chat()` unchanged signature, now built via `buildRequestBody(..., false)`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void buildRequestBody_specialCharactersSurviveRoundTrip() throws Exception {
    var messages = List.<Map<String,Object>>of(
        Map.of("role","user","content","line1\nline2 \"quoted\" \\ end"));
    String body = OllamaClient.buildRequestBody("llama3", messages, "[{\"type\":\"function\"}]", true);
    var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    assertThat(root.path("model").asText()).isEqualTo("llama3");
    assertThat(root.path("stream").asBoolean()).isTrue();
    assertThat(root.path("tools").toString()).isEqualTo("[{\"type\":\"function\"}]");
    assertThat(root.path("messages").get(0).path("content").asText())
        .isEqualTo("line1\nline2 \"quoted\" \\ end"); // hand-rolled builder corrupted exactly this
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OllamaClientTest`
Expected: COMPILATION ERROR (`buildRequestBody` does not exist)

- [ ] **Step 3: Implement**

`buildRequestBody`: build a `LinkedHashMap` (`model`,`messages`,`stream`, optional `tools` parsed via `mapper.readTree`) and `mapper.writeValueAsString`. Delete the private hand-rolled `toJsonArray/toJsonObject/valueToJson/escapeJson` (dead after both call sites switch). `chatStream`: `HttpRequest` as in `chat` but body from `buildRequestBody(...,true)` and `BodyHandlers.ofLines()`; iterate lines into one `OllamaStreamParser` (with a 5-min request timeout, same as `chat`); after the stream ends return `parser.toResponseJson()` (parser tolerates a missing `done` line — `toResponseJson` finalizes regardless).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=OllamaClientTest`
Expected: PASS (HTTP path itself remains untested offline, same as `chat` today)

- [ ] **Step 5: Commit**

`git add src/main/java/com/example/agent/llm/OllamaClient.java src/test/java/com/example/agent/llm/OllamaClientTest.java && git commit -m "feat: jackson request body + chatStream with NDJSON line feed"`

---

### Task 4: TokenEstimator + heuristic implementation

**Files:**
- Create: `src/main/java/com/example/agent/session/TokenEstimator.java`
- Create: `src/main/java/com/example/agent/session/HeuristicTokenEstimator.java`
- Test: `src/test/java/com/example/agent/session/HeuristicTokenEstimatorTest.java`

**Interfaces:**
- Produces: `public interface TokenEstimator { int estimate(String text); }` and `public final class HeuristicTokenEstimator implements TokenEstimator` with `estimate` = null/empty → 0, else `(length + 3) / 4` (ceil of chars/4).

- [ ] **Step 1: Write the failing test**

```java
@Test
void heuristic_isCeilOfCharsOverFour() {
    var e = new HeuristicTokenEstimator();
    assertThat(e.estimate(null)).isZero();
    assertThat(e.estimate("")).isZero();
    assertThat(e.estimate("abcd")).isEqualTo(1);   // 4 chars
    assertThat(e.estimate("abcde")).isEqualTo(2);  // 5 chars -> ceil
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=HeuristicTokenEstimatorTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Implement both types** (one public type per file)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=HeuristicTokenEstimatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

`git add src/main/java/com/example/agent/session/TokenEstimator.java src/main/java/com/example/agent/session/HeuristicTokenEstimator.java src/test/java/com/example/agent/session/HeuristicTokenEstimatorTest.java && git commit -m "feat: pluggable token estimator with chars/4 heuristic"`

---

### Task 5: ContextTrimmer policy + AgentSession.buildContext overload

**Files:**
- Create: `src/main/java/com/example/agent/session/ContextTrimmer.java`
- Modify: `src/main/java/com/example/agent/session/AgentSession.java`
- Test: `src/test/java/com/example/agent/session/ContextTrimmerTest.java`

**Interfaces:**
- Consumes: `TokenEstimator` (Task 4), `AgentSession.SessionEntry` record (existing).
- Produces:
  - `public final class ContextTrimmer` with `public static List<AgentSession.SessionEntry> trim(List<AgentSession.SessionEntry> path, int maxTokens, TokenEstimator estimator)` — pure, returns unmodifiable list in original order
  - `AgentSession`: `public List<SessionEntry> buildContext(int maxTokens, TokenEstimator estimator)` — delegates to `trim(buildContext(), ...)`; existing `buildContext()` untouched
  - Entry cost = `estimator.estimate(textOf(entry))` where `textOf` = `String.valueOf(entry.data())` (SessionMessage/ToolResultMessage both stringify usefully via record toString)
  - Policy: leading `type=="message" && data instanceof SessionMessage m && m.role()==SYSTEM` entries always kept (their cost always counted). Remaining entries grouped into blocks newest-first; **a `type=="tool_result"` entry is atomic with its immediately preceding entry** (same block). Blocks added newest→oldest while running total ≤ `maxTokens`; first block that overflows stops selection. Dropped count N > 0 → one marker entry `new SessionEntry("trim", null, "", "trim_marker", "[... " + N + " entries trimmed ...]")` inserted right after the system prefix. Guarantee: the newest entry is always present even when its block alone exceeds `maxTokens`; result is never empty.

- [ ] **Step 1: Write the failing tests**

```java
private AgentSession.SessionEntry msg(AgentSession.MessageRole role, String text) {
    return new AgentSession.SessionEntry("id", null, "", "message",
        new AgentSession.SessionMessage(role, text));
}
private AgentSession.SessionEntry toolResult(String text) {
    return new AgentSession.SessionEntry("id", null, "", "tool_result",
        new AgentSession.ToolResultMessage("t", ToolResult.success(text)));
}
// Helper note: ToolResult.success(text) — use the existing factory from AgentSessionTest's conventions;
// if the existing factory is named differently, match it.

@Test
void largeBudget_identicalToFullPath_noMarker() { /* 1 system + 3 user msgs, budget 10_000 → same 4 entries, no trim_marker */ }

@Test
void tightBudget_keepsSystemAndNewest_marksDroppedMiddle() {
    // system("S") + user(fill 100 tokens) + user(fill 100) + user("latest")
    // budget just fits S + latest → result = [system, marker(N=2), latest], marker data contains "2"
}

@Test
void toolResultNeverOrphaned_atBoundary() {
    // system + assistant("call") + toolResult("out") + user("latest"); budget fits system+latest+pair exactly
    // → pair assistant+tool_result both present when either is present
}

@Test
void newestAlwaysPresent_evenWhenOverBudget() {
    // system + one huge user entry alone over budget → result [system, marker(0 omitted when N==0 → actually no marker), huge]
    // pin: result contains system and the huge entry; no trim_marker when nothing was dropped
}

@Test
void markerNotPersistedToTree() {
    // trim via a real AgentSession (buildContext(1, estimator) with entries appended)
    // → session.getEntries().size() unchanged; returned list may contain marker
}
```

Fill the `/* */` bodies: exact assertions per the interface contract above (marker `data()` string equals `"[... 2 entries trimmed ...]"`, sizes via `assertThat(list).hasSize(...)`, `extracting(SessionEntry::type)`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=ContextTrimmerTest`
Expected: COMPILATION ERROR (`ContextTrimmer` missing)

- [ ] **Step 3: Implement `ContextTrimmer.trim` + `AgentSession` overload**

Algorithm: split path into `systemPrefix` / `rest`; block-partition `rest` (tool_result attaches to previous entry's block); cost per block = sum of entry costs; iterate blocks newest→oldest accumulating; stop at first overflow; if NOTHING selected and `rest` non-empty → force-include the newest block alone; build result `systemPrefix + (dropped>0 ? marker : []) + selectedOldestFirst`; wrap `List.copyOf`.

- [ ] **Step 4: Run tests + full suite to verify no regression**

Run: `mvn -q test`
Expected: all PASS including the pre-existing 18 tests (`buildContext()` untouched)

- [ ] **Step 5: Commit**

`git add src/main/java/com/example/agent/session/ContextTrimmer.java src/main/java/com/example/agent/session/AgentSession.java src/test/java/com/example/agent/session/ContextTrimmerTest.java && git commit -m "feat: head+tail context trimming with tool_result pair integrity"`

---

### Task 6: LlmAgentLoop streaming switch + README/limitations update

**Files:**
- Modify: `src/main/java/com/example/agent/llm/LlmAgentLoop.java`
- Modify: `README.md` (limitations section)
- Test: no new test (loop requires a live client; verified by compilation + full suite, consistent with today's coverage)

**Interfaces:**
- Consumes: `OllamaClient.chatStream(...)` (Task 3).
- Produces: `public LlmAgentLoop enableStreaming(java.util.function.Consumer<String> onDelta)` — fluent, sets internal `streaming=true`, stores nullable consumer; default remains blocking `chat()`. In `run()`, the single call site branches: `streaming ? client.chatStream(model, messages, toJson(toolDefinitions), onDelta) : client.chat(model, messages, toJson(toolDefinitions))`.

- [ ] **Step 1: Implement the switch** (fields default `private boolean streaming = false; private java.util.function.Consumer<String> onDelta = null;`)

- [ ] **Step 2: Full build + suite green**

Run: `mvn -q test`
Expected: all PASS

- [ ] **Step 3: Update README limitations**

Replace the "OllamaClient 无流式" and "无 token 窗口裁剪" lines with one-paragraph usage notes: `enableStreaming(onDelta)` + `buildContext(maxTokens, estimator)`, and note the remaining honest gaps (ModelRegistry 路径未统一、未接真实流量). Also bump the test count mention if README states one.

- [ ] **Step 4: Commit**

`git add src/main/java/com/example/agent/llm/LlmAgentLoop.java README.md && git commit -m "feat: opt-in streaming in agent loop; README limitations updated"`

- [ ] **Step 5: Tag & wrap**

`git tag v0.2.0 && git push origin master --tags` (push may hit the known flaky network; retry after a pause per session-12 pattern, or leave unpushed and note it)
