# Phase 5: Testing Strategy

**Goal:** Comprehensive test coverage: protocol serialization, MCP server unit tests, integration tests with mock WebSocket, and in-game tests via Fabric GameTest API.

**Architecture:** Tests are organized in three tiers: (1) Unit tests in `:protocol` and `:mcp-server` that need no Minecraft, (2) Integration tests in `:mcp-server` using a mock WebSocket server, (3) Game tests in `:mod:fabric` using fabric-gametest-api-v1.

---

## Task 5.1: Protocol Serialization Tests

**File:** `protocol/src/test/kotlin/dev/parrot/protocol/`

**Build deps** (protocol/build.gradle.kts):
```kotlin
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}
tasks.test { useJUnitPlatform() }
```

**Run:** `./gradlew :protocol:test`

### Tests to implement:

- **MessageSerializationTest**: Round-trip every message type (Hello, HelloAck, Ping, Pong, Goodbye, GoodbyeAck, ActionRequest, ActionResult, QueryRequest, QueryResult, CommandRequest, CommandResult, SubscribeRequest, SubscribeAck, UnsubscribeRequest, UnsubscribeAck, PushEvent, ErrorResponse, BatchRequest, BatchResult)
- **PolymorphicSerializationTest**: Deserialize raw JSON strings with `type` discriminator, verify correct subtype. Test unknown type throws exception.
- **ErrorCodeSerializationTest**: Each ErrorCode enum serializes as string, not ordinal
- **ConnectionInfoTest**: ConnectionInfo round-trips, handles null pid
- **BatchSerializationTest**: Batch with mixed queries, empty batch, error at specific index
- **ConsequenceSerializationTest**: All 12 consequence types serialize with correct structure

### Commit

```bash
git add protocol/src/test/
git commit -m "feat: add comprehensive protocol serialization tests"
```

---

## Task 5.2: MCP Server Unit Tests

**File:** `mcp-server/src/test/kotlin/dev/parrot/mcp/`

**Build deps** (mcp-server/build.gradle.kts):
```kotlin
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.test { useJUnitPlatform() }
```

**Run:** `./gradlew :mcp-server:test`

### Tests to implement:

- **ConfigDiscoveryTest**: Env var precedence, connection file reading, malformed file fallback, default values
- **ToolValidationTest**: Missing required params, invalid types, batch with action methods rejected, consequence_wait bounds
- **BridgeCorrelationTest**: Unique IDs, out-of-order response correlation, timeout cleanup, disconnect fails all pending
- **BackoffTest**: Exponential sequence (1s, 2s, 4s, 8s, 16s, 30s cap), reset on success. Use TestCoroutineScheduler.
- **GracefulDegradationTest**: Tools return helpful errors when disconnected, capability-based degradation (no gui_observation -> get_screen error)

### Commit

```bash
git add mcp-server/src/test/
git commit -m "feat: add MCP server unit tests (config, validation, backoff, degradation)"
```

---

## Task 5.3: Integration Tests (Mock WebSocket)

**File:** `mcp-server/src/test/kotlin/dev/parrot/mcp/integration/`

**Additional build deps:**
```kotlin
testImplementation("io.ktor:ktor-server-core:3.4.1")
testImplementation("io.ktor:ktor-server-cio:3.4.1")
testImplementation("io.ktor:ktor-server-websockets:3.4.1")
```

### MockMinecraftServer

A Ktor CIO WebSocket server for testing:
- Listens on random port (port 0)
- Validates hello, responds with hello_ack
- Configurable canned responses keyed by (type, method)
- Can simulate disconnection after N messages
- Records all received messages for assertions

### Integration tests:

- **HandshakeTest**: Connect, hello/hello_ack exchange, verify capabilities stored
- **QueryFlowTest**: Send get_block, verify correct query message received, verify response returned
- **ActionFlowTest**: Send do_interact_block, verify action message with consequence_wait
- **CommandFlowTest**: Send run_command, verify command message (no leading /)
- **ReconnectionTest**: Disconnect mid-session, verify reconnect, verify pending requests failed
- **HeartbeatTest**: Server sends ping, verify pong response
- **BatchFlowTest**: Send batch with 3 queries, verify ordered results
- **SubscriptionFlowTest**: Subscribe, receive push event, unsubscribe
- **AuthFailureTest**: Wrong token, verify no reconnect attempt (terminal)
- **E2E McpToolTest**: Full MCP Server -> MinecraftBridge -> MockServer -> response -> CallToolResult

### Commit

```bash
git add mcp-server/src/test/kotlin/dev/parrot/mcp/integration/
git commit -m "feat: add integration tests with mock WebSocket server"
```

---

## Task 5.4: Game Tests (Fabric GameTest API)

**File:** `mod/fabric/src/gametest/kotlin/dev/parrot/mod/test/`

**Build config** (mod/fabric/build.gradle.kts):
```kotlin
loom {
    runs {
        create("gametest") {
            server()
            name("Game Test")
            vmArg("-Dfabric-api.gametest")
            vmArg("-Dfabric-api.gametest.report-file=${project.layout.buildDirectory.get()}/gametest/report.xml")
        }
    }
}
```

**Run:** `./gradlew :mod:fabric:runGametest`

### Game tests:

- **WebSocketLifecycleTest**: Server running -> WebSocket listening -> connect + hello succeeds
- **ConnectionFileTest**: ~/.parrot/connection.json exists with valid port + token
- **GetBlockTest**: Place diamond_block, query via WebSocket, verify response
- **GetEntitiesTest**: Spawn cow, query nearby entities, verify found
- **InteractBlockTest**: Place chest, interact, verify screen_opened consequence
- **RunCommandTest**: Execute `/time set day`, verify success + output
- **EventSubscriptionTest**: Subscribe to block_changed, break block, verify event received, unsubscribe, break another, verify no event

### Helper: ParrotTestHelper

```kotlin
object ParrotTestHelper {
    fun connectAndAuth(): WebSocketClient {
        val connInfo = ConnectionFileManager.read()!!
        val client = WebSocketClient("ws://127.0.0.1:${connInfo.port}/")
        client.send(ParrotJson.encodeToString(ParrotMessage.serializer(), Hello(id = "1", authToken = connInfo.token)))
        val ack = client.receive(timeout = 5000)
        // verify hello_ack
        return client
    }
}
```

### Commit

```bash
git add mod/fabric/src/gametest/
git commit -m "feat: add Fabric game tests for WebSocket, queries, actions, and events"
```

---

## Test Summary

| Scope | Command | Count |
|-------|---------|-------|
| Protocol unit tests | `./gradlew :protocol:test` | ~25 tests |
| MCP server unit tests | `./gradlew :mcp-server:test` | ~30 tests |
| MCP server integration | `./gradlew :mcp-server:test --tests "*.integration.*"` | ~15 tests |
| Fabric game tests | `./gradlew :mod:fabric:runGametest` | ~7 tests |
| All (except game tests) | `./gradlew test` | ~70 tests |
