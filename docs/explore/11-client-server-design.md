# Client vs Server Side Design: MCP Minecraft Mod

## Executive Summary

The MCP bridge mod **must run on both client and server sides**. Screen/GUI observation is inherently client-only, while world state access and command execution are server-only. In singleplayer (integrated server), both sides share the same JVM, making communication trivial. For dedicated servers, client-side observations must be relayed to the server via custom network packets, where the WebSocket server lives.

---

## 1. Understanding Minecraft's Side Architecture

### 1.1 Physical vs Logical Sides

Minecraft has **two orthogonal concepts** of "sides":

| Concept | Client | Server |
|---------|--------|--------|
| **Physical** | The Minecraft client JAR (with rendering, audio, input) | The dedicated server JAR (headless, no GUI classes) |
| **Logical** | The thread that handles rendering, input, screen display | The thread that runs game simulation, ticks, world state |

**Key insight**: In singleplayer, a single physical client contains BOTH logical sides — an internal logical server runs in the same JVM process as the logical client.

### 1.2 Singleplayer: The Integrated Server

When a player opens a singleplayer world:
1. The physical client launches an **integrated server** (logical server) in the same JVM
2. The client connects to this internal server as the logical client
3. This is conceptually like a `localhost` connection — but without actual network sockets
4. Both the client thread and server thread share the same process memory space

**Implication for our mod**: In singleplayer, the mod's client-side and server-side components can communicate via shared in-memory state (e.g., a `ConcurrentLinkedQueue`) without network packets.

### 1.3 Multiplayer: Dedicated Server

When connecting to a dedicated server:
1. The physical server runs ONLY the logical server (no rendering, no GUI classes)
2. The physical client runs the logical client AND connects over the network
3. Client-only classes (annotated `@Environment(EnvType.CLIENT)` or loaded via `Dist.CLIENT`) are **stripped from the server JAR entirely**

**Implication for our mod**: In multiplayer, client-side observations MUST be transmitted to the server via custom network packets. The mod must be installed on both client and server.

---

## 2. API Classification: Client-Only vs Server-Only

### 2.1 Client-Only APIs (Logical Client Thread)

These APIs exist only on the physical client. Referencing them from server-side code causes `ClassNotFoundException` on dedicated servers.

| API | Loader | Purpose |
|-----|--------|---------|
| `ScreenEvents.AFTER_INIT` | Fabric | Detect when any screen opens |
| `ScreenEvents.remove(screen)` | Fabric | Detect screen closing |
| `ScreenKeyboardEvents` / `ScreenMouseEvents` | Fabric | Input interception on screens |
| `Screens.getButtons(screen)` | Fabric | Access clickable widgets |
| `ScreenEvent.Opening` / `ScreenEvent.Closing` | NeoForge | Screen open/close detection |
| `ScreenEvent.Init` / `ScreenEvent.Render` | NeoForge | Screen lifecycle |
| `ScreenEvent.MouseButtonPressed` / `KeyPressed` | NeoForge | Input on screens |
| `MinecraftClient.getInstance()` / `Minecraft.getInstance()` | Both | Client singleton — screens, player, options |
| `ClientTickEvents` | Fabric | Per-tick client hooks |
| `AbstractSoundInstance` and audio APIs | Both | Sound playback |
| All rendering/shader APIs | Both | Visual display |
| `ClientPlayConnectionEvents` | Fabric | Client network events |
| `ClientPlayNetworking` | Fabric | Send packets from client |
| `ClientPacketDistributor.sendToServer()` | NeoForge | Send packets from client |

**Critical for our mod**: Screen/GUI observation (detecting what's open, reading displayed contents, seeing inventory slots) is **entirely client-side**. This is the mod's primary "observation" capability.

### 2.2 Server-Only APIs (Logical Server Thread)

These APIs are the authoritative source of game state.

| API | Loader | Purpose |
|-----|--------|---------|
| `MinecraftServer` | Both | Server singleton — worlds, players, commands |
| `ServerLevel` / `ServerWorld` | Both | Authoritative world state |
| `Level.getBlockState(pos)` | Both | Block queries (server is authoritative) |
| `Level.getBlockEntity(pos)` | Both | Block entity data (chests, signs, etc.) |
| `Level.getEntities(...)` | Both | Entity queries |
| `ServerPlayerInteractionManager` | Both | Simulate player actions |
| `MinecraftServer.getCommands()` | Both | Command execution |
| `ServerLifecycleEvents` | Fabric | Server start/stop hooks |
| `ServerTickEvents` | Fabric | Per-tick server hooks |
| `ServerPlayNetworking` | Fabric | Receive packets from clients |
| `PacketDistributor.sendToPlayer()` | NeoForge | Send packets to clients |
| `PlayerList` / `ServerPlayer` | Both | Player management |

**Critical for our mod**: All world state queries, command execution, and entity inspection run server-side. The WebSocket server should live here (server thread owns the authoritative game state).

### 2.3 Shared APIs (Available on Both Sides)

| API | Notes |
|-----|-------|
| `Level` (base class) | Use `level.isClientSide()` to check which logical side |
| `Entity`, `LivingEntity`, `Player` | Base classes available everywhere; server is authoritative |
| `AbstractContainerMenu` | Container menu logic — slots, items. Synced from server to client |
| `ItemStack`, `BlockState`, `BlockPos` | Data classes, side-agnostic |
| `Component` (text) | Text formatting, side-agnostic |
| Brigadier command framework | Command tree structure is shared; registration hooks differ |

**Important note on containers**: `AbstractContainerMenu` and its slot contents are synced from the server to the client. After sync (typically <1 tick / 50ms), the client has a copy of the container contents. This means inventory reading can work from either side, but the server is authoritative.

---

## 3. Architecture Design

### 3.1 Where Does the WebSocket Server Live?

**Answer: On the server side (logical server thread).**

Rationale:
- The server has authoritative world state
- The server can execute commands
- The server persists across client disconnects (in multiplayer)
- The server thread is the "hub" — it's always running when a world is loaded

Lifecycle:
- **Fabric**: Start on `ServerLifecycleEvents.SERVER_STARTED`, stop on `ServerLifecycleEvents.SERVER_STOPPING`
- **NeoForge**: Start on `ServerStartedEvent`, stop on `ServerStoppingEvent`

### 3.2 Mod Component Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Physical Client                           │
│                                                              │
│  ┌──────────────────────┐    ┌────────────────────────────┐ │
│  │   Client Component    │    │  Integrated Server         │ │
│  │                       │    │  (singleplayer only)       │ │
│  │  - Screen observers   │    │                            │ │
│  │  - GUI state capture  │───►│  - WebSocket server        │ │
│  │  - Input interception │ pk │  - World state access      │ │
│  │  - Client tick hooks  │ ts │  - Command execution       │ │
│  │                       │    │  - Entity inspection       │ │
│  └──────────────────────┘    │  - Event aggregation       │ │
│           │                   └────────────────────────────┘ │
│           │ (in singleplayer:                                │
│           │  shared memory)                                  │
│           │                                                  │
│           │ (in multiplayer:                                 │
│           │  custom packets)                                 │
│           ▼                                                  │
└─────────────────────────────────────────────────────────────┘
            │ (multiplayer only: network)
            ▼
┌─────────────────────────────────────────────────────────────┐
│                  Dedicated Server                            │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │   Server Component                                      │ │
│  │                                                          │ │
│  │  - WebSocket server (always here)                       │ │
│  │  - World state access                                   │ │
│  │  - Command execution                                    │ │
│  │  - Entity inspection                                    │ │
│  │  - Receives client observations via custom packets      │ │
│  │  - Aggregates all data for MCP responses                │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
            ▲
            │ WebSocket
            ▼
┌─────────────────────────────────────────────────────────────┐
│              External MCP Server (Rust/TypeScript)           │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 The Three Deployment Scenarios

#### Scenario A: Singleplayer (Most Common for Debugging)

- Mod runs on physical client (both logical sides in same JVM)
- Client component observes screens, captures GUI state
- Server component runs WebSocket, accesses world state
- Communication: **Shared in-memory state** (no packets needed)
  - A singleton `ObservationBus` or `ConcurrentLinkedQueue<Observation>` shared between threads
  - Client thread enqueues observations; server thread dequeues them on tick
- This is the simplest and most performant scenario

#### Scenario B: Dedicated Server — Mod on Both Sides

- Server has the WebSocket and world state access
- Client mod captures screen events and sends them as custom packets
- Server mod receives packets, correlates with world state, responds to MCP
- **Full capability**: Both world state AND GUI observation available
- Custom packet types needed:
  - `ScreenOpenedPacket` — client→server: which screen opened, container type
  - `ScreenContentsPacket` — client→server: slot contents of open container
  - `ScreenClosedPacket` — client→server: screen was closed
  - `ClientObservationPacket` — client→server: generic observation wrapper

#### Scenario C: Dedicated Server — Mod on Server Only

- WebSocket server works, world state is accessible, commands execute
- **No GUI observation** — can't detect what screens players see
- Can still read container contents server-side via block entities
- Can still simulate interactions via `ServerPlayerInteractionManager`
- **Degraded but functional**: ~70% of capabilities remain
- This is a valid "lite" mode for server-side automation that doesn't need GUI insight

#### Scenario D: Client Only — No Server Component

- **Not viable for our use case**
- Client can observe screens but cannot execute commands authoritatively
- Cannot reliably access world state (client only has what's synced to it)
- No good place to host the WebSocket (client lifecycle is unstable)
- **Do not support this scenario**

---

## 4. Client-to-Server Observation Relay

### 4.1 Why It's Needed

In multiplayer, the MCP server connects to the WebSocket on the dedicated server. But screen events only fire on the client. We need a bridge:

```
Client Screen Event → Custom Packet → Server → WebSocket → MCP Server
```

### 4.2 Relay Protocol Design

Define a set of custom network packets for relaying client observations:

```
ObservationPacket (client → server):
  - type: enum { SCREEN_OPENED, SCREEN_CLOSED, SCREEN_CONTENTS, CHAT_RECEIVED, TOAST_SHOWN }
  - timestamp: long (game tick)
  - payload: JSON string or structured data

ScreenOpenedPayload:
  - screenClass: String (e.g., "GenericContainerScreen")
  - menuType: ResourceLocation (e.g., "minecraft:generic_9x3")
  - title: Component (display title)
  - containerId: int

ScreenContentsPayload:
  - containerId: int
  - slots: List<SlotData> { index, itemId, count, components }

ScreenClosedPayload:
  - containerId: int
```

### 4.3 Implementation Per Loader

**Fabric:**
```
// Registration (common-ish, but uses Fabric networking API):
PayloadTypeRegistry.playC2S().register(ObservationPayload.ID, ObservationPayload.CODEC);
ServerPlayNetworking.registerGlobalReceiver(ObservationPayload.ID, (payload, context) -> {
    // Handle on server thread
    observationBus.enqueue(payload.toObservation());
});

// Client side (in client source set):
ClientPlayNetworking.send(new ObservationPayload(...));
```

**NeoForge:**
```
// Registration:
@SubscribeEvent
public static void register(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar("1");
    registrar.playToServer(ObservationPayload.TYPE, ObservationPayload.STREAM_CODEC,
        (payload, context) -> observationBus.enqueue(payload.toObservation()));
}

// Client side (in Dist.CLIENT class):
ClientPacketDistributor.sendToServer(new ObservationPayload(...));
```

### 4.4 Singleplayer Optimization

In singleplayer, skip the network packet overhead entirely:

```java
if (isIntegratedServer()) {
    // Direct in-memory relay — no serialization needed
    ServerSideHandler.getObservationBus().enqueue(observation);
} else {
    // Multiplayer — send over the network
    sendPacketToServer(new ObservationPayload(observation));
}
```

Detection:
- Fabric: `MinecraftClient.getInstance().isLocalServer()` or check if `MinecraftClient.getInstance().getSingleplayerServer() != null`
- NeoForge: `Minecraft.getInstance().hasSingleplayerServer()`

---

## 5. Side Safety: Preventing Crashes

### 5.1 The Problem

If common/server code accidentally references a client-only class (e.g., `Minecraft`, `Screen`, `AbstractSoundInstance`), it will crash on dedicated servers with `ClassNotFoundException` because those classes are stripped from the server JAR.

### 5.2 Fabric's Approach

**`@Environment(EnvType.CLIENT)` annotation:**
- Applied by Minecraft/Fabric to client-only classes and methods
- Classes with this annotation don't exist on the server
- Example: `AbstractSoundInstance`, all `Screen` subclasses, `MinecraftClient`

**Compile-time safety with split source sets:**
```groovy
loom {
    splitEnvironmentSourceSets()
    mods {
        "minecraft-mcp" {
            sourceSet sourceSets.main    // common code
            sourceSet sourceSets.client  // client-only code
        }
    }
}
```
- `src/main/java/` — common code (server + client safe)
- `src/client/java/` — client-only code (can reference `@Environment(EnvType.CLIENT)` classes)
- The build system **enforces** that `main` cannot import `client` classes at compile time
- Both source sets compile into a single JAR

**Recommendation**: Always use `splitEnvironmentSourceSets()` — it catches side violations at compile time.

### 5.3 NeoForge's Approach

**`@Mod(dist = Dist.CLIENT)` for entrypoints:**
```java
@Mod("minecraft-mcp")
public class MinecraftMcpMod {
    // Runs on BOTH physical sides
}

@Mod(value = "minecraft-mcp", dist = Dist.CLIENT)
public class MinecraftMcpClientMod {
    // Only loaded on physical client — can safely reference Minecraft.getInstance(), Screen, etc.
}
```

**`FMLEnvironment.dist` for runtime checks:**
```java
if (FMLEnvironment.dist == Dist.CLIENT) {
    // Safe to reference client classes here — but use a separate helper class
    // to avoid classloading the client class on the server
    ClientHelper.doClientThing();
}
```

**Important pattern**: Never directly reference client classes in the check — use an indirection layer:
```java
// WRONG — ClientScreen gets classloaded even before the if-check evaluates
if (FMLEnvironment.dist == Dist.CLIENT) {
    new ClientScreen().show(); // ClassNotFoundException on server!
}

// RIGHT — ClientHelper is only loaded when called
if (FMLEnvironment.dist == Dist.CLIENT) {
    ClientHelper.showScreen(); // ClientHelper lives in a client-only package
}
```

### 5.4 Our Mod's Side Safety Strategy

For the MultiLoader Template approach:

```
common/
  src/main/java/
    com.example.minecraftmcp/
      McpBridge.java              — WebSocket server, command handling (server-safe)
      ObservationBus.java         — Thread-safe observation queue (side-agnostic)
      protocol/                   — MCP protocol, JSON serialization (side-agnostic)
      api/
        ClientObserver.java       — Interface for client-side observations
        ServerStateProvider.java  — Interface for server-side state access

fabric/
  src/main/java/                  — Fabric server-side: lifecycle, events, networking registration
  src/client/java/                — Fabric client-side: ScreenEvents listeners, ClientObserver impl

neoforge/
  src/main/java/
    NeoForgeMod.java              — @Mod, server-side events
    NeoForgeClientMod.java        — @Mod(dist=CLIENT), client-side events, ClientObserver impl
```

---

## 6. What If the Mod Is Only On One Side?

### 6.1 Server-Only Installation

| Capability | Available? | Notes |
|------------|-----------|-------|
| World state queries | Yes | Full access to blocks, entities, biomes |
| Command execution | Yes | Any command via CommandDispatcher |
| Block entity reading | Yes | Chests, furnaces, signs — server-authoritative |
| Entity inspection | Yes | Health, equipment, position, NBT |
| Simulate interactions | Yes | ServerPlayerInteractionManager |
| Screen/GUI observation | **No** | No client component to observe screens |
| Container contents via GUI | **No** | Can read via block entities, but not the GUI view |
| Chat message interception | Yes | Server-side chat events |
| Player position/state | Yes | ServerPlayer has full state |

**Verdict**: Server-only is a viable "lite" mode (~70% capability). The main loss is GUI observation.

### 6.2 Client-Only Installation

| Capability | Available? | Notes |
|------------|-----------|-------|
| Screen/GUI observation | Yes | Full access to screens and displayed contents |
| Rendered entity observation | Partial | Can see what's rendered, but not authoritative |
| World state | Partial | Client has synced copy, not authoritative, limited range |
| Command execution | **No** | Cannot execute server commands |
| Block entity reading | Partial | Only if synced to client |
| WebSocket server hosting | **Risky** | Client lifecycle is unstable (disconnects, dimension changes) |

**Verdict**: Client-only is **not recommended**. The client lacks authoritative state, cannot execute commands, and is an unreliable host for the WebSocket server.

### 6.3 Graceful Degradation Strategy

The mod should detect which components are available and advertise capabilities to the MCP server:

```json
// Capability advertisement on WebSocket connect
{
  "capabilities": {
    "world_state": true,
    "command_execution": true,
    "gui_observation": true,    // false if no client component
    "entity_inspection": true,
    "container_reading": true,
    "screen_interaction": true   // false if no client component
  },
  "deployment": "singleplayer",  // or "dedicated_with_client", "dedicated_server_only"
  "minecraft_version": "1.21.8",
  "loader": "fabric"
}
```

---

## 7. Threading Model

### 7.1 Thread Inventory

| Thread | Owns | Safe Operations |
|--------|------|-----------------|
| Server Thread | World state, entities, commands | All server-side reads/writes |
| Client Thread | Screens, rendering, input | Screen inspection, GUI reading |
| Netty I/O Threads | WebSocket connections | Receiving/sending WebSocket frames |

### 7.2 Thread Safety Rules

1. **WebSocket → Server**: Incoming MCP commands arrive on Netty I/O threads. Must be enqueued and processed on server tick:
   ```
   server.execute(() -> { /* process command */ });
   ```

2. **Client → Server** (singleplayer): Client observations enqueued to `ConcurrentLinkedQueue`, dequeued on server tick.

3. **Client → Server** (multiplayer): Custom packets are received on the server's network thread, then handled on the server thread via the packet handler's context.

4. **Server → WebSocket**: Responses can be sent from any thread (Netty handles this), but data collection must happen on the server thread.

### 7.3 Request-Response Flow

```
MCP Server ──WebSocket──► Netty I/O Thread
                                │
                          CommandQueue.enqueue(request)
                                │
                                ▼
Server Tick ──────────► CommandQueue.dequeue()
    │                         │
    │    ┌────────────────────┤
    │    │                    │
    │    ▼                    ▼
    │  Server-side        Need client
    │  data (fast)        observation?
    │    │                    │
    │    │              ┌─────┴──────┐
    │    │              │ SP: read   │ MP: request
    │    │              │ shared     │ via packet,
    │    │              │ memory     │ await response
    │    │              └─────┬──────┘
    │    │                    │
    │    ▼                    ▼
    │  Aggregate all data
    │    │
    │    ▼
    │  Send response via WebSocket
```

---

## 8. Recommendations

### 8.1 Primary Deployment Target

**Singleplayer** should be the primary target. This is where mod developers spend most of their debugging time, and it's the simplest architecture (shared JVM, no packet relay needed).

### 8.2 Mod Structure

1. **Require both sides** but gracefully degrade if client component is missing
2. Use Fabric's `splitEnvironmentSourceSets()` and NeoForge's `@Mod(dist=...)` for compile-time safety
3. Define a `ClientObserver` interface in common code; implement it per-loader in client source sets
4. WebSocket server always lives on the server side
5. Use `ObservationBus` (concurrent queue) as the side-agnostic bridge

### 8.3 Custom Packet Set

Define a minimal set of client→server packets:
- `ScreenStatePacket` — screen opened/closed/contents changed
- `ClientEventPacket` — generic client-side event wrapper (toasts, chat HUD, etc.)
- `CapabilityPacket` — client advertises what it can observe (sent on join)

And server→client:
- `ObservationRequestPacket` — server asks client to capture current screen state
- `ConfigSyncPacket` — server sends observation configuration to client

### 8.4 MultiLoader Considerations

The client/server split adds complexity to the cross-loader strategy:
- **Common module**: `ObservationBus`, `ClientObserver` interface, protocol, WebSocket server
- **Fabric main**: Server lifecycle hooks, packet registration (server side)
- **Fabric client**: `ScreenEvents` listeners, `ClientObserver` impl, packet sending
- **NeoForge main**: `@Mod` class, server event subscriptions, packet registration
- **NeoForge client**: `@Mod(dist=CLIENT)` class, `ScreenEvent` subscriptions, `ClientObserver` impl

The ~85-90% code sharing estimate from the crossloader report holds. The client/server split is mostly about **where** code runs, not **what** code does — the observation logic is similar across loaders.

---

## 9. Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Client classloading on server | **High** (crash) | Split source sets (Fabric), `@Mod(dist=...)` (NeoForge), strict interface boundaries |
| Packet desync in multiplayer | **Medium** | Version the packet protocol, handle missing client gracefully |
| Integrated server detection bugs | **Low** | Well-established APIs: `isLocalServer()`, `hasSingleplayerServer()` |
| Client observation latency in MP | **Low** | Screen sync is <1 tick; packet round-trip adds ~1-2 ticks |
| Mod only on server (no GUI) | **Low** | Graceful degradation — advertise capabilities, MCP server adapts |
| Thread safety bugs | **Medium** | Strict queue-based communication, never access cross-thread state directly |

---

## 10. Conclusion

The client/server side split is a well-understood Minecraft architectural pattern, and both Fabric and NeoForge provide clean mechanisms for handling it. Our mod needs to be a **dual-side mod** with:

1. A **server component** that hosts the WebSocket, accesses world state, and executes commands
2. A **client component** that observes screens/GUIs and relays observations to the server
3. An **in-memory bridge** for singleplayer and a **custom packet relay** for multiplayer
4. **Compile-time side safety** via Fabric split source sets and NeoForge dist-filtered mod classes

This design supports all deployment scenarios while keeping the architecture clean and the failure modes well-defined.
