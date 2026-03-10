# NeoForge Feasibility Report: MCP Server ↔ Minecraft Mod

## Executive Summary

**Feasibility: HIGH.** NeoForge provides all the necessary APIs to build a mod that exposes game state and accepts commands via WebSocket. The event bus system is comprehensive, world state is fully readable, and Netty (bundled with Minecraft) provides WebSocket server capabilities. No fundamental blockers were identified.

---

## 1. Event Bus System

NeoForge provides two event buses:

- **Game Bus** (`NeoForge.EVENT_BUS`): Fires gameplay events (block breaks, entity spawns, player interactions, tick events)
- **Mod Bus**: Fires lifecycle events (registration, setup, config)

Events are subscribed via:
- `@SubscribeEvent` annotation on methods
- `@EventBusSubscriber` annotation on classes (auto-discovered)
- `IEventBus#addListener()` for programmatic registration

Events support **cancellation** (`ICancellableEvent`), **priority ordering** (HIGHEST→LOWEST), and **side filtering** (`Dist.CLIENT` / `Dist.SERVER`).

**Assessment:** The event bus is the backbone for intercepting all game activity. It's well-designed and comprehensive.

---

## 2. Screen/GUI Interception

### Key Events
| Event | Description |
|-------|-------------|
| `ScreenEvent.Opening` | Fired **before** any screen opens; can modify or cancel |
| `ScreenEvent.Closing` | Fired **before** a screen closes |
| `ScreenEvent.Init` | Screen initialization (widget setup) |
| `ScreenEvent.Render` | Screen drawing |
| `ScreenEvent.MouseButtonPressed/Released` | Mouse input on screens |
| `ScreenEvent.KeyPressed/Released` | Keyboard input on screens |
| `ScreenEvent.CharacterTyped` | Character input |
| `ScreenEvent.MouseDragged/Scrolled` | Mouse drag and scroll |
| `ScreenEvent.RenderInventoryMobEffects` | Mob effects in inventory UI |

### Key Classes
- `ClientboundOpenScreenPacket`: Carries `containerId`, `MenuType<?>`, and `title` (Component)
- `AbstractContainerMenu`: Base class for container menus (chests, furnaces, etc.)
- `RegisterMenuScreensEvent`: Links MenuTypes to screen implementations
- `Screen#onClose()` / `Screen#removed()`: Teardown hooks

### Container Access
The `Container` interface (implemented by `ChestBlockEntity`, `BarrelBlockEntity`, `HopperBlockEntity`, `ShulkerBoxBlockEntity`, `FurnaceBlockEntity`, etc.) provides:
- `getContainerSize()` → number of slots
- `getItem(int slot)` → ItemStack at slot
- `isEmpty()` → quick empty check
- `countItem(Item)` → count specific items

**Assessment:** Full interception of GUI lifecycle. When a chest opens, the mod can capture the `ScreenEvent.Opening`, identify the `MenuType`, read all container slots via `AbstractContainerMenu#getSlot()`, and report contents back over WebSocket.

---

## 3. Block Interaction Events

| Event | Description |
|-------|-------------|
| `PlayerInteractEvent.RightClickBlock` | Right-clicking a block (both sides) |
| `PlayerInteractEvent.LeftClickBlock` | Left-clicking a block |
| `PlayerInteractEvent.RightClickItem` | Using an item (both sides) |
| `PlayerInteractEvent.RightClickEmpty` | Right-click empty space (client) |
| `PlayerInteractEvent.LeftClickEmpty` | Left-click empty space (client) |
| `PlayerInteractEvent.EntityInteract` | Right-click an entity |
| `PlayerInteractEvent.EntityInteractSpecific` | Right-click entity (specific location) |
| `UseItemOnBlockEvent` | Using item on a block |
| `AttackEntityEvent` | Attacking an entity |
| `BlockEvent.BreakEvent` | Player breaking a block (server) |
| `BlockEvent.EntityPlaceEvent` | Block placement |
| `BlockDropsEvent` | Block drops determined |

Each event provides: `getHand()`, `getItemStack()`, `getPos()`, `getFace()`, `getLevel()`, `getSide()`.

**Assessment:** Complete coverage of all player-world interactions. Every click and block operation can be intercepted.

---

## 4. Entity Inspection

### Entity Events
| Event | Description |
|-------|-------------|
| `LivingAttackEvent` | Entity attacked |
| `LivingDamageEvent` | Damage applied |
| `LivingDeathEvent` | Entity death |
| `MobSpawnEvent` | Mob spawn lifecycle |
| `MobDespawnEvent` | Mob despawn check |
| `LivingEquipmentChangeEvent` | Equipment changes |
| `MobEffectEvent.*` | Potion effects (Added/Expired/Remove) |
| `LivingChangeTargetEvent` | AI target changes |
| `EntityTickEvent.Pre/Post` | Per-entity per-tick |

### Entity Access API
- `Level#getEntities(Entity, AABB, Predicate)` → find entities in area
- `Level#getEntity(int)` → by entity ID
- `Entity` class provides: position, velocity, health (LivingEntity), equipment, NBT data, type

**Assessment:** Entities are fully inspectable. The mod can enumerate all entities in a region, read their health, equipment, effects, position, and AI targets.

---

## 5. World State Reading

### Level (World) API
| Method | Returns |
|--------|---------|
| `Level#getBlockState(BlockPos)` | Block at position |
| `Level#getBlockEntity(BlockPos)` | Block entity (chest, furnace, etc.) |
| `Level#getFluidState(BlockPos)` | Fluid at position |
| `Level#getBiomeManager()` | Biome data |
| `Level#getDayTime()` | Current time |
| `Level#isRaining()` / `isThundering()` | Weather |
| `Level#getEntities(...)` | Entity queries |

### Server API
| Method | Returns |
|--------|---------|
| `MinecraftServer#getAllLevels()` | All dimensions |
| `MinecraftServer#getPlayerList()` | Online players |
| `MinecraftServer#getCommands()` | Command dispatcher |
| `MinecraftServer#createCommandSourceStack()` | Server-level command source |
| `MinecraftServer#getGameRules()` | Game rules |
| `MinecraftServer#getTickCount()` | Current tick |

**Assessment:** The entire world state is readable — blocks, entities, players, weather, time, biomes, inventories. No gaps.

---

## 6. WebSocket/HTTP Server Feasibility

### Approach: Embedded Netty WebSocket Server

Minecraft bundles **Netty** (`io.netty`), which includes full WebSocket support:
- `WebSocketServerProtocolHandler`
- `WebSocketFrame` codecs
- `ServerBootstrap` for binding to a port

The mod would:
1. Start a Netty WebSocket server on a configurable port (e.g., 8765) during `FMLCommonSetupEvent`
2. Accept connections from the external MCP server
3. Use a **thread-safe command queue** for incoming requests
4. Process the queue on `ServerTickEvent.Pre` (ensuring game state access is on the server thread)
5. Send results back over WebSocket asynchronously

### Thread Safety Architecture
```
MCP Server ──WebSocket──► Mod WebSocket Handler
                              │
                              ▼
                         Command Queue (ConcurrentLinkedQueue)
                              │
                              ▼ (ServerTickEvent.Pre)
                         Command Executor (server thread)
                              │
                              ▼
                         Result Queue → WebSocket response
```

### Precedent
Mods like **Dynmap** and **BlueMap** already embed HTTP servers within Minecraft mods using similar patterns, proving this approach is viable in production.

### Alternative: Stdio/Process Communication
Instead of WebSocket, the mod could communicate via a named pipe or localhost TCP socket. However, WebSocket is preferred because:
- Bidirectional and event-driven
- JSON-friendly (matches MCP protocol)
- Well-supported by Netty already in the classpath

**Assessment:** Fully feasible. Netty is already available, the pattern is proven by existing mods, and the tick-event queue pattern handles thread safety cleanly.

---

## 7. Command Execution

The mod can execute arbitrary Minecraft commands programmatically:
```java
MinecraftServer server = ...;
Commands commands = server.getCommands();
CommandSourceStack source = server.createCommandSourceStack();
commands.performPrefixedCommand(source, "give @p diamond 64");
```

For player-specific commands, use `ServerPlayer` from `PlayerList`.

**Assessment:** Any command can be executed programmatically — give, tp, summon, effect, etc. This enables a rich "action" vocabulary for the MCP protocol.

---

## 8. Key Risks and Considerations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Thread safety (WebSocket thread vs server thread) | Medium | Command queue + ServerTickEvent pattern |
| Client-side vs server-side event split | Medium | Some ScreenEvents are client-only; need careful side handling |
| Mod compatibility | Low | Event bus is designed for multi-mod coexistence |
| Performance impact of per-tick polling | Low | Only process queued commands; no polling overhead if idle |
| Minecraft version updates | Medium | NeoForge abstracts some but not all vanilla changes |
| Port conflicts | Low | Configurable port with fallback |

---

## 9. Recommended MCP Action Vocabulary

Based on available APIs, the mod could support these action types:

### Read Actions (Query State)
- `get_block(x, y, z)` → block state, block entity data
- `get_entities(x, y, z, radius)` → nearby entities with health/equipment/effects
- `get_inventory(x, y, z)` → container contents at position
- `get_player_inventory(player)` → player inventory
- `get_player_info(player)` → position, health, food, effects, equipment
- `get_world_info()` → time, weather, dimension, game rules
- `get_screen()` → currently open screen type and contents (client-side)

### Write Actions (Execute Commands)
- `run_command(command)` → execute any Minecraft command
- `interact_block(x, y, z, hand)` → simulate right-click on block
- `attack_entity(entity_id)` → simulate attack
- `use_item(slot)` → simulate item use
- `click_slot(slot, button)` → interact with open container

### Event Subscriptions (Push Notifications)
- `subscribe("block_break")` → get notified on block breaks
- `subscribe("entity_death")` → entity death events
- `subscribe("screen_open")` → GUI open/close events
- `subscribe("chat")` → chat messages

---

## 10. Conclusion

NeoForge is **fully capable** of supporting this MCP server integration. The combination of:
- Comprehensive event bus for intercepting all game activity
- Full world state read access via Level and MinecraftServer APIs
- Container/inventory inspection via the Container interface
- Screen lifecycle events for GUI interception
- Bundled Netty for WebSocket server capabilities
- Command dispatcher for programmatic command execution

...means there are **no fundamental technical barriers**. The main engineering challenge is designing a clean, thread-safe bridge between the WebSocket layer and the game thread, which is a solved problem with established patterns.

**Estimated complexity: Medium.** The core bridge architecture is straightforward; the bulk of the work is in serializing diverse game state into a consistent JSON protocol.
