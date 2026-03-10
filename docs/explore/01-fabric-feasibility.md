# Fabric Mod Feasibility Report: MCP Server Bridge for Minecraft

## Executive Summary

**Verdict: HIGHLY FEASIBLE** — Fabric provides all the necessary APIs and extension points to build a mod that exposes Minecraft game state and actions via WebSocket to an external MCP server. The combination of Fabric API's event system, screen APIs, networking infrastructure, and the Mixin escape hatch covers every required capability.

---

## 1. Architecture Overview

The proposed system has three components:

```
[MCP Server (Rust/TypeScript)] <--WebSocket--> [Fabric Mod (Java)] <--Game APIs--> [Minecraft]
```

The Fabric mod acts as a bridge:
- Embeds a WebSocket server inside the Minecraft process
- Receives commands from the external MCP server (e.g., "open chest at X,Y,Z")
- Executes actions using Minecraft/Fabric APIs
- Hooks into events to detect results (e.g., "chest screen opened, contains these items")
- Sends structured JSON responses back via WebSocket

---

## 2. Screen/GUI Interception

### Capability: FULLY SUPPORTED

Fabric API's `fabric-screen-api-v1` module provides comprehensive screen lifecycle events.

**Key Classes:**
- `ScreenEvents.BEFORE_INIT` / `ScreenEvents.AFTER_INIT` — Global events fired for ALL screens. The screen instance is passed, allowing `instanceof` checks to detect specific screen types (e.g., `AbstractInventoryScreen`, `GenericContainerScreen`, `CraftingScreen`).
- `ScreenEvents.remove(screen)` — Detect when a screen closes.
- `ScreenEvents.beforeRender(screen)` / `afterRender(screen)` — Per-frame render hooks.
- `ScreenEvents.beforeTick(screen)` / `afterTick(screen)` — Per-tick hooks.
- `ScreenKeyboardEvents` / `ScreenMouseEvents` — Input interception on screens.
- `Screens.getButtons(screen)` — Access all `ClickableWidget` instances on a screen.

**How to detect a chest opening:**
```java
ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
    if (screen instanceof GenericContainerScreen containerScreen) {
        // A chest/barrel/shulker box screen just opened
        AbstractContainerMenu handler = containerScreen.getMenu();
        // Read all slots
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getItem();
            // Report item type, count, NBT, etc.
        }
    }
});
```

**Reading inventory contents of ANY open container:**
- Access via `MinecraftClient.getInstance().player.containerMenu`
- Iterate `containerMenu.slots` to read all `ItemStack` objects
- Each `Slot` exposes: `getItem()`, `index`, `container` (the backing `Inventory`)

### Limitations
- Screen detection is **client-side only** — the mod needs a client-side component
- Container contents sync from server to client, so there may be a brief delay (typically <1 tick)

---

## 3. Block Interaction APIs

### Capability: FULLY SUPPORTED

**Fabric API Events (`fabric-events-interaction-v0`):**
- `UseBlockCallback` — Right-click on blocks (opening chests, pressing buttons, etc.)
- `AttackBlockCallback` — Left-click on blocks (mining)
- `PlayerBlockBreakEvents.BEFORE` / `.AFTER` — Block breaking lifecycle
- `UseItemCallback` — Right-click with items in hand

**Programmatic block interaction:**
- `ServerPlayerInteractionManager.interactBlock()` — Simulate right-click on a block (server-side)
- `MinecraftClient.getInstance().interactionManager.interactBlock()` — Client-side block interaction
- Direct block state reading: `world.getBlockState(pos)` returns `BlockState` with all properties
- Block entity data: `world.getBlockEntity(pos)` for chests, furnaces, signs, etc.

**Example: Programmatically open a chest:**
```java
// Server-side: simulate player right-clicking on a chest
BlockPos chestPos = new BlockPos(x, y, z);
BlockState state = world.getBlockState(chestPos);
state.useItemOn(itemInHand, world, player, hand, hitResult);
// This triggers the vanilla chest-opening logic, which sends a screen to the player
```

---

## 4. Entity Inspection

### Capability: FULLY SUPPORTED

**Fabric API Events (`fabric-entity-events-v1`):**
- `ServerLivingEntityEvents` — Damage, death, equipment changes
- `ServerEntityCombatEvents` — Combat events
- `ServerPlayerEvents` — Player copy, respawn
- `EntitySleepEvents` — Sleep lifecycle
- `ServerEntityWorldChangeEvents` — Dimension changes
- `EntityElytraEvents` — Elytra flight

**Direct entity access (vanilla APIs):**
- `world.getEntitiesOfClass(EntityClass, boundingBox)` — Query entities in an area
- `entity.position()` — Get coordinates
- `entity.getHealth()`, `entity.getMaxHealth()` — Health info (LivingEntity)
- `entity.getArmorSlots()`, `entity.getHandSlots()` — Equipment
- `entity.getTags()` — Entity tags
- `entity.getType()` — Entity type registry
- `entity.saveWithoutId(CompoundTag)` — Full NBT serialization of entity state

**Interaction Events:**
- `UseEntityCallback` — Right-click on entities
- `AttackEntityCallback` — Left-click on entities

---

## 5. World State Reading

### Capability: FULLY SUPPORTED

**Block state queries:**
- `world.getBlockState(pos)` — Returns `BlockState` with all block properties
- `world.getBlockEntity(pos)` — Access block entity data (inventories, signs, etc.)
- `world.getBiome(pos)` — Biome at position
- `world.getChunk(chunkX, chunkZ)` — Chunk data access
- `world.getLightLevel(pos)` — Light level
- `world.isDay()` / `world.isNight()` — Time queries

**World scanning:**
- Can iterate over loaded chunks and blocks
- `PlayerLookup.world(serverWorld)` — Find all players in a world (Fabric API)
- `PlayerLookup.tracking(entity)` — Find players tracking an entity

---

## 6. Event Hooking System

### Capability: EXCELLENT

Fabric API provides a rich event system via `Event<T>` instances with `register()` methods.

**Available event categories:**
| Category | Key Events |
|----------|-----------|
| Lifecycle | `ServerLifecycleEvents.SERVER_STARTING/STARTED/STOPPING/STOPPED` |
| Ticking | `ServerTickEvents.START/END_SERVER_TICK`, `START/END_LEVEL_TICK`, `ClientTickEvents.START/END_CLIENT_TICK` |
| Interaction | `UseBlockCallback`, `UseEntityCallback`, `UseItemCallback`, `AttackBlockCallback`, `AttackEntityCallback` |
| Block Breaking | `PlayerBlockBreakEvents.BEFORE/AFTER` |
| Screen | `ScreenEvents.BEFORE_INIT/AFTER_INIT`, per-screen `remove/render/tick` events |
| Entity | `ServerLivingEntityEvents`, `ServerEntityCombatEvents`, `EntitySleepEvents` |
| Player | `ServerPlayerEvents`, `PlayerPickItemEvents` |
| Commands | `CommandRegistrationCallback` |
| Networking | `ServerPlayConnectionEvents`, `ClientPlayConnectionEvents` |

**Custom events** can also be created via `EventFactory.createArrayBacked()`.

---

## 7. WebSocket/HTTP Server Embedding

### Capability: FULLY FEASIBLE

**Recommended approach: Use Minecraft's bundled Netty**

Minecraft already includes Netty as a dependency (it's used for the internal networking protocol). The Fabric mod can leverage Netty's WebSocket support (`io.netty.handler.codec.http.websocketx`) with **zero additional dependencies**.

**Alternative libraries:**
- `org.java-websocket:Java-WebSocket` — Lightweight, single-purpose (~40KB)
- Jetty WebSocket — Heavier but very robust
- Undertow — Moderate weight, good performance

**Threading considerations:**
- The WebSocket server MUST run on its own thread(s)
- All game state access must be scheduled onto the main game thread:
  - Server-side: `MinecraftServer.execute(Runnable)` or `server.submit(Runnable)`
  - Client-side: `MinecraftClient.execute(Runnable)`
- Responses can be sent from any thread (Netty handles this)

**Lifecycle management:**
```java
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    // Start WebSocket server on port 25580
    websocketServer.start();
});

ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
    // Graceful shutdown
    websocketServer.stop();
});
```

---

## 8. Mixin Escape Hatch

### Capability: UNLIMITED EXTENSIBILITY

For any functionality not covered by Fabric API events, Fabric's Mixin system provides direct access to Minecraft internals:

- `@Inject` — Add code at method entry/exit/specific points
- `@Redirect` — Replace method calls within methods
- `@Accessor` / `@Invoker` — Access private fields/methods
- `@ModifyVariable` / `@ModifyArg` — Modify local variables or arguments

**Useful Mixin targets for this project:**
- `MinecraftClient.setScreen()` — Intercept ALL screen changes (not just those firing events)
- `AbstractContainerMenu.setItem()` — Real-time inventory slot change detection
- `ChatComponent` — Intercept chat messages
- `ClientPacketListener` — Intercept specific incoming packets
- `Commands` — Hook into command processing

**Risk**: Mixins target specific Minecraft internals that may change between versions. Strategy: use Fabric API events first, Mixins only for gaps.

---

## 9. Specific Use Cases & Implementation Path

### "Open a chest" → "GUI opened, chest contains these items"

1. **Command received** via WebSocket: `{"action": "interact_block", "pos": [10, 64, -20]}`
2. **Server-side**: Use `ServerPlayerInteractionManager` to simulate right-click on the block
3. **Client-side**: `ScreenEvents.AFTER_INIT` detects `GenericContainerScreen` opening
4. **Read contents**: Iterate `containerMenu.slots` to get all `ItemStack` data
5. **Send response**: `{"result": "screen_opened", "type": "chest", "slots": [{"slot": 0, "item": "diamond", "count": 5}, ...]}`

### "Look at what entities are nearby"

1. **Command**: `{"action": "scan_entities", "radius": 16}`
2. **Server-side**: `world.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(16))`
3. **Serialize**: Entity type, position, health, name for each
4. **Response**: `{"entities": [{"type": "zombie", "pos": [12, 64, -18], "health": 20}, ...]}`

### "Read the sign at position X"

1. **Command**: `{"action": "read_block_entity", "pos": [10, 65, -20]}`
2. **Server-side**: `world.getBlockEntity(pos)` → cast to `SignBlockEntity`
3. **Read text**: `sign.getText(true/false)` for front/back
4. **Response**: `{"type": "sign", "front": ["Line 1", "Line 2", ...], "back": [...]}`

---

## 10. Risks and Challenges

| Risk | Severity | Mitigation |
|------|----------|------------|
| Thread safety between WebSocket and game threads | **Medium** | Use `server.execute()` / `client.execute()` for all game state access |
| Client vs Server side split | **Medium** | Mod needs both sides; screen/GUI detection is client-only, world state is server-side. For singleplayer, both run in same process. For dedicated servers, need a connected client. |
| Version breakage (Mixins) | **Low** | Minimize Mixin use; prefer Fabric API events. Fabric API is versioned and maintained. |
| Performance with frequent state polling | **Low** | Use event-driven approach rather than polling; only scan when requested |
| Mod compatibility | **Low** | Fabric API events are designed for multi-mod compatibility |
| Container sync delay | **Very Low** | Server→client inventory sync takes <1 tick (50ms). Acceptable for MCP use case. |

---

## 11. Fabric-Specific Advantages

1. **Lightweight**: Fabric loader is minimal, fast to load, and doesn't modify vanilla jars
2. **Mixin-first design**: Deep access to vanilla code when needed
3. **Active maintenance**: Fabric API is actively maintained for latest Minecraft versions (currently up to 1.21.8)
4. **Netty reuse**: Zero-dependency WebSocket server by reusing Minecraft's bundled Netty
5. **Strong event system**: Array-backed events with clean callback interfaces
6. **Transfer API**: Sophisticated item/fluid transfer system for inventory operations
7. **Screen Handler API**: Extended screen handler factories for custom container GUIs

---

## 12. Comparison Notes (vs NeoForge)

| Aspect | Fabric |
|--------|--------|
| Screen Events | `ScreenEvents` with BEFORE_INIT/AFTER_INIT, per-screen render/tick/remove |
| Interaction Events | Comprehensive: UseBlock/UseEntity/UseItem/AttackBlock/AttackEntity/PlayerBlockBreak |
| Networking | `PayloadTypeRegistry` + `ServerPlayNetworking` / `ClientPlayNetworking` |
| Extensibility | Mixin system (same as SpongePowered Mixin used across the ecosystem) |
| Inventory Access | Vanilla `Container` + Fabric Transfer API v1 |
| Lifecycle | Full server/client lifecycle events |
| Mod Weight | Lightweight — Fabric loader + API are minimal |
| Update Speed | Typically first to update to new Minecraft versions |

---

## 13. Conclusion

Building an MCP server bridge as a Fabric mod is **highly feasible**. Every required capability is either directly supported by Fabric API or achievable via Mixins:

- **Screen/GUI interception**: First-class support via `ScreenEvents`
- **Inventory reading**: Direct access via `containerMenu.slots`
- **Block interaction**: `UseBlockCallback` + programmatic interaction APIs
- **Entity inspection**: Full vanilla API access + Fabric entity events
- **World state**: Complete read access to blocks, biomes, light, time
- **External communication**: WebSocket via bundled Netty (zero extra deps)
- **Event hooking**: Rich event system covering lifecycle, ticking, interaction, screens
- **Escape hatch**: Mixin system for anything not covered by events

The recommended implementation strategy is:
1. Use Fabric API events as the primary integration point
2. Use Mixins sparingly for gaps (e.g., real-time inventory slot change detection)
3. Embed a Netty-based WebSocket server for external communication
4. Implement a JSON-based command/response protocol
5. Handle threading carefully with `server.execute()` / `client.execute()`
