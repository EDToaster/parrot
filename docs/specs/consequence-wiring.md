# Spec: Wire Up ConsequenceCollector

## Problem

`ConsequenceCollector` exists but is completely disconnected. Actions and commands return empty `consequences` arrays. The `consequenceWait` field on `ActionRequest` and `CommandRequest` is parsed but ignored.

This means Parrot can't tell Claude "I right-clicked that chest and a screen opened" — one of the key features that makes the tool useful for interactive debugging.

## Current State

- `ConsequenceCollector` class exists at `mod/common/.../engine/ConsequenceCollector.kt` with `startCollecting()`, `onConsequence()`, and `tick()` methods — fully implemented, just disconnected
- `CommandQueue.dispatch()` handles `ActionRequest` and `CommandRequest` but returns results immediately with no consequence collection
- `ParrotEngine.tick()` calls `commandQueue.drainAndExecute()` but never ticks a collector
- `SubscriptionManager.dispatch()` fires events to WebSocket subscriptions but doesn't feed consequences. Additionally, `eventSender` is never assigned, so subscriptions themselves are also broken (events are built then silently dropped).
- `AttackEntityHandler` and `InteractEntityHandler` read `params.int("entity_id")` but MCP schema declares `uuid` (string) — entity action tools are broken
- Protocol types are correct: `ActionResult.consequences` and `CommandResult.consequences` accept `List<Consequence>`
- `BatchHandler` enforces `isReadOnly` on sub-commands — batch cannot contain actions, so batch + consequences is a non-issue

## Desired Behavior

1. When an `ActionRequest` arrives with `consequenceWait = N`:
   - Execute the action handler immediately
   - Open a collection window for `N` ticks using `ConsequenceCollector.startCollecting()`
   - Game events during the window feed into the collector
   - After `N` ticks (or wall-clock timeout), return `ActionResult` with collected consequences

2. Same for `CommandRequest` with its `consequenceWait`

3. Queries (`QueryRequest`) return immediately as today — no consequence collection

4. Game events that produce consequences:
   - Block changes near the action origin
   - Screen opened/closed
   - Entity spawned/removed near the action origin
   - Inventory changed

## Architecture: Deferred Response Pattern

The current `CommandQueue.drainAndExecute()` processes commands synchronously. For consequence collection, responses must be deferred across ticks:

```
Tick 100: ActionRequest arrives (consequenceWait=5)
          handler executes (right-clicks chest)
          collection window opens (deadline = tick 105)
          dispatch() returns null — response NOT sent yet
          PendingCommand moves to deferred list

Tick 101: screen_opened event fires → collector.onConsequence() captures it
Tick 102: inventory_changed fires → captured
Tick 103-104: quiet

Tick 105: collector.tick() completes the handle (deadline reached)
          drainAndExecute() phase 2 finds completed handle
          builds ActionResult with consequences=[screen_opened, inventory_changed]
          completes the future → response sent to client
```

`drainAndExecute()` gains two phases:
- **Phase 2 (first)**: Check deferred responses whose CollectionHandles are complete, send responses
- **Phase 1 (second)**: Drain new commands from the queue as today

Phase 2 runs first because `collector.tick()` runs before `drainAndExecute()` in `ParrotEngine.tick()`, so completed handles are already done.

## Filter Strategy: Hybrid (Smart Defaults + Override)

Each `do_` MCP tool gets sensible default `consequenceFilter` and `consequenceWait` values. Claude can override via optional params. Override replaces defaults entirely (no merging).

### Default Filter Map (MCP server layer)

| Tool | Default Filter | Spatial Origin | Default Wait | Rationale |
|------|---------------|----------------|-------------|-----------|
| `do_interact_block` | `screen_opened`, `block_changed`, `inventory_changed` | From x,y,z params | 5 ticks | Opens containers, activates redstone |
| `do_attack_block` | `block_changed` | From x,y,z params | 3 ticks | Breaking changes block state |
| `do_interact_entity` | `screen_opened`, `inventory_changed` | None (entity moves) | 5 ticks | Villager trades, merchant screens |
| `do_attack_entity` | `entity_removed` | None | 5 ticks | May kill entity |
| `do_click_slot` | `inventory_changed` | None | 2 ticks | Slot clicks change inventory |
| `do_close_screen` | `screen_closed`, `inventory_changed` | None | 2 ticks | Closing is near-instant |
| `do_set_held_slot` | *(empty)* | None | 0 ticks | No game consequences |
| `do_send_chat` | `chat_message` | None | 3 ticks | Chat echo / command response |
| `run_command` | *(null = all types)* | None | 3 ticks | Commands can do anything |

### Override Params on MCP Tools

Each `do_` tool gains two optional params in its schema:

- `consequence_filter`: `string[]` — Override event types to collect. Replaces the default entirely.
- `consequence_wait`: `integer` — Override ticks to wait. `0` disables consequence collection.

Tool descriptions communicate the defaults:
```
"Right-click/interact with a block. Waits 5 ticks for block_changed,
screen_opened, inventory_changed within 8 blocks. Override with
consequence_filter/consequence_wait if needed."
```

### Spatial Origin

Block-targeted actions (`interact_block`, `attack_block`) auto-extract `(x, y, z)` from their own params and pass as spatial origin to `ConsequenceCollector`. This enables the existing 8-block radius filter, preventing noise from unrelated world changes. Entity and UI actions skip spatial filtering.

### Param Stripping

`consequence_filter`, `consequence_wait` are MCP-layer concerns. `handleAction()` extracts them, then strips them from the `params` JsonObject before forwarding to the mod handler.

## Files to Modify

### Mod: `ParrotEngine.kt` (~10 lines)
- Add `AtomicReference<ConsequenceCollector?>` field
- Create `ConsequenceCollector` in `start()`, pass to `CommandQueue`
- Call `consequenceCollector.tick()` in `tick()` **before** `drainAndExecute()`
- Wire `SubscriptionManager.consequenceSink` callback to feed the collector
- Clear collector in `stop()`

### Mod: `SubscriptionManager.kt` (~3 lines)
- Add `consequenceSink: ((String, Long, JsonObject, Vec3?) -> Unit)?` callback field (mirrors existing `eventSender` pattern)
- Call it in `dispatch()` alongside the subscription loop

### Mod: `PendingCommand.kt` (~15 lines)
- Add optional `collectionHandle: CollectionHandle?` field
- Add `DeferredResult` sealed class to store handler results while waiting:
  ```kotlin
  sealed class DeferredResult {
      data class Action(val result: JsonObject, val tick: Long) : DeferredResult()
      data class Command(val output: String, val returnValue: Int?, val tick: Long) : DeferredResult()
  }
  ```
- Add optional `deferredResult: DeferredResult?` field

### Mod: `CommandQueue.kt` (~45 lines)
- Accept `ConsequenceCollector` as constructor parameter
- Add `private val deferred = mutableListOf<PendingCommand>()`
- `dispatch()` returns `ParrotMessage?` (null = deferred)
- In `ActionRequest` branch: if `consequenceWait > 0` and collector is present, start collecting, store DeferredResult, add to deferred list, return null
- Same pattern for `CommandRequest` branch
- `drainAndExecute()` phase 2: iterate deferred list, check `handle.future.isDone`, build final response with consequences, complete the pending future
- `buildDeferredResponse()` helper to construct `ActionResult`/`CommandResult` from `DeferredResult` + consequences

### MCP Server: `ToolRegistrar.kt` (~60 lines)
- Add `ConsequenceDefaults` data class and `DEFAULT_FILTERS` map
- Modify `handleAction()` to:
  - Look up defaults for the method
  - Extract `consequence_filter`/`consequence_wait` overrides from args
  - Extract spatial origin from block params when applicable
  - Strip consequence params from args before forwarding
  - Pass `consequenceWait` and `consequenceFilter` to `ActionRequest`
- Add `consequence_filter` and `consequence_wait` to each `do_` tool's `inputSchema`
- Update tool descriptions to mention consequence defaults
- **Update batch tool description** from "Execute multiple commands in sequence" to "Execute multiple read-only queries in a single request. Actions are not supported in batch — use individual do_ tools for actions that need consequence feedback."

### No Changes Needed
- `ConsequenceCollector.kt` — works as-is
- `CollectionHandle` — works as-is
- `protocol/ParrotMessage.kt` — `ActionRequest.consequenceWait/Filter` and `ActionResult.consequences` already exist
- `BatchHandler.kt` — already rejects actions

## Thread Safety

All changes operate on the server tick thread:
- `drainAndExecute()` called from `ParrotEngine.tick()` on server thread
- `collector.tick()` called from same thread, immediately before
- `deferred` list only accessed in `drainAndExecute()` — single-threaded
- `collector.onConsequence()` called from `SubscriptionManager.dispatch()` which fires on server thread
- `enqueue()` called from Netty threads → `ConcurrentLinkedQueue` (already safe)
- `CompletableFuture.complete()` on server thread; Netty reads via `.thenAccept()` (safe via future's memory barrier)

No new synchronization needed.

## Edge Cases

| Case | Handling |
|------|---------|
| **Error during collection** | `onConsequence()` iterates handles; wrap in try-catch per-handle (~2 lines) to prevent one bad event from breaking the dispatch loop |
| **Concurrent windows** | `ConsequenceCollector` already supports multiple active handles via `CopyOnWriteArrayList`. Each gets independent filtering and deadline. |
| **Client disconnects mid-collection** | Handle completes normally at deadline, response send fails on closed Netty channel (silent). Optional: check `channel.isActive` in phase 2 to skip dead channels early. |
| **`consequenceWait = 0`** | `if (consequenceWait > 0)` check skips deferral. Returns immediately with `consequences = emptyList()`. |
| **Server lag / tick stall** | `ConsequenceCollector.tick()` checks both tick deadline AND wall-clock deadline. Wall-clock fallback handles stalls. |
| **Handler throws** | Error response returned immediately. No collection window opened. |
| **Deferred completions vs maxPerTick** | Deferred completions do NOT count toward `maxPerTick`. They're finishing old work (copying a list + completing a future), not executing new handlers. |

## Batch

Batch is unaffected. `BatchHandler` enforces `isReadOnly` on all sub-commands, rejecting actions with `BATCH_ACTIONS_FORBIDDEN`. Since consequences only apply to actions, batch never produces consequences. No changes needed.

If action sequencing with per-step consequences is needed in the future, that's a job for the planned scripting language, not batch.

## Prerequisite Fixes

These are existing bugs discovered during exploration that must be fixed as part of this work, since they affect the same event and action pathways.

### Fix 1: Wire `SubscriptionManager.eventSender`

`SubscriptionManager.eventSender` (line 16) is declared but **never assigned**. When `dispatch()` fires an event, it builds the JSON and calls `eventSender?.invoke(sub.id, event)` — which silently drops every event because the callback is null. The entire subscribe → poll_events flow is broken.

**Files to modify:**

- `ParrotEngine.kt` or `ParrotWebSocketServer.kt`: Assign `eventSender` to a function that writes the event JSON to the WebSocket channel for the subscription's owner. The `Subscription` data class already carries a `channelId` field for routing.

```kotlin
// In ParrotEngine.start() or when the WebSocket server is wired up:
subscriptionManager.eventSender = { subId, eventJson ->
    wsServer.currentSession?.let { session ->
        session.channel.writeAndFlush(/* encode eventJson as WebSocket text frame */)
    }
}
```

This fix is a prerequisite for consequences because consequences use the same `dispatch()` pathway. If `dispatch()` never fires, the `consequenceSink` callback added by this spec also won't fire.

### Fix 2: Entity handler param mismatch (`uuid` vs `entity_id`)

The MCP tool schemas for `do_interact_entity` and `do_attack_entity` declare `uuid` (string), but the mod handlers read `entity_id` (int):

- `AttackEntityHandler.kt:14`: `params.int("entity_id")` + `level.getEntity(entityId)`
- `InteractEntityHandler.kt:15`: `params.int("entity_id")` + `level.getEntity(entityId)`

These tools are broken — Claude gets UUIDs from `get_entities`, passes them to `do_attack_entity`, and the handler can't find the key.

**Fix:** Change both handlers to accept `uuid` (string) and look up via `ServerLevel.getEntity(UUID)`:

```kotlin
// Before (broken):
val entityId = params.int("entity_id")
val entity = level.getEntity(entityId)

// After:
val uuid = java.util.UUID.fromString(params.string("uuid"))
val entity = (level as? net.minecraft.server.level.ServerLevel)?.getEntity(uuid)
    ?: throw ParrotException(ErrorCode.ENTITY_NOT_FOUND, "Entity with UUID $uuid not found")
```

**Files to modify:**
- `mod/common/.../commands/actions/AttackEntityHandler.kt` (~2 lines)
- `mod/common/.../commands/actions/InteractEntityHandler.kt` (~2 lines)

UUID is the right choice over numeric entity ID because:
- UUIDs are stable across ticks; numeric IDs are session-internal
- `get_entities` and `get_entity` query tools already return UUIDs — the API should be consistent
- No MCP schema changes needed since the schema already says `uuid`

## Test Plan

- Unit test: `ConsequenceCollector` collects events within window, ignores events after deadline
- Unit test: Spatial filtering excludes distant events (>8 blocks)
- Unit test: Type filtering respects `consequenceFilter` list
- Unit test: `CommandQueue` deferred pattern — action with `consequenceWait=3` defers response, completes after 3 ticks with consequences
- Unit test: `CommandQueue` with `consequenceWait=0` returns immediately with empty consequences
- Unit test: Multiple concurrent collection windows get independent consequences
- Integration test: Send `ActionRequest` with `consequenceWait=3`, mock a `block_changed` event 1 tick later, verify consequences array in response
- Integration test: Verify `consequence_filter` and `consequence_wait` MCP params are forwarded correctly and stripped from handler params
- Game test: Right-click a chest with `do_interact_block`, verify `screen_opened` appears in consequences

### Prerequisite fix tests
- Integration test: Subscribe to `block_changed`, trigger a block change, verify `poll_events` returns the event (validates `eventSender` wiring)
- Integration test: Call `do_attack_entity` with a UUID string, verify the entity is attacked (validates UUID lookup fix)
- Integration test: Call `do_interact_entity` with a UUID string, verify interaction succeeds
