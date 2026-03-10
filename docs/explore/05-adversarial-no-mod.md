# Feasibility Report: Minecraft MCP Server WITHOUT a Mod

**Explorer:** explorer-no-mod (adversarial)
**Question:** Can we build a call-and-response MCP server for Minecraft without requiring a mod?

## Executive Summary

A mod is **not strictly required** for a call-and-response MCP server pattern. Three mod-free architectures exist, each capable of reading game state and issuing commands. However, **deep mod debugging** (inspecting mod internals, custom registries, mod-specific GUI behavior) **does require a mod**. The optimal solution is likely a hybrid: a protocol proxy for observation + a lightweight mod for introspection.

---

## Approaches Evaluated

### 1. RCON + /data Commands

**How it works:** Connect to the server's RCON port (TCP, default 25575), authenticate, and send any server command. Use `/data get` to read NBT data from blocks, entities, players, and storage.

**Capabilities:**
- Read player inventory: `/data get entity @p Inventory[{Slot:0b}].id`
- Read chest contents via block entity NBT at coordinates
- Read/write command storage for persistent data
- Execute any server command (`/execute`, `/function`, `/scoreboard`, etc.)
- Modify entity and block NBT via `/data merge` and `/data modify`

**Limitations:**
- Returns **plain text only** — NBT output must be parsed (fragile, format may change between versions)
- Max payload: 1446 bytes in, 4096 bytes out per packet (fragmentation for large responses)
- **No event detection** — cannot tell when a player opens a chest, clicks a GUI, or interacts with anything
- Failed/unknown commands produce **no response** (silent failure)
- Requires server-side access (`server.properties` must enable RCON)
- Only works on dedicated servers, not singleplayer (unless opened to LAN with RCON somehow)

**Verdict:** Good for polling-based state inspection. Simplest to implement. Poor for event-driven interactions.

---

### 2. Protocol Proxy (node-minecraft-protocol / PrismarineJS)

**How it works:** A proxy sits between the Minecraft client and server, intercepting all network packets in both directions. The player connects to the proxy instead of directly to the server.

**Capabilities:**
- **Passively observe ALL game traffic** including:
  - `open_window` — when any GUI opens (chest, furnace, crafting table, etc.), with window type and title
  - `window_items` — full container contents (item IDs, counts, all slots)
  - `set_slot` — individual slot updates in real-time
  - `close_window` — when GUIs close
  - `block_entity_data` — block entity NBT updates
  - Entity spawn, metadata, position, and state packets
  - Chat messages and command responses
- **Inject packets** — send commands as if the player typed them
- **Structured data** — packets are parsed into typed objects, not text
- Full version support (1.8 through 1.21.5+) via minecraft-data

**Limitations:**
- Player must connect **through the proxy** (changes connection workflow)
- Adds a network hop (minor latency)
- Must keep up with protocol changes per Minecraft version (PrismarineJS handles this well)
- Cannot observe mod-internal state — only sees what flows through the vanilla protocol
- Custom mod packets (plugin channels) are visible but require mod-specific knowledge to parse
- **Node.js dependency** — the MCP server would need to integrate with or wrap a Node.js process (or reimplement protocol parsing in Rust)

**Verdict:** Most powerful mod-free approach. Provides event-driven observation of everything the client sees. Best for the "open a chest → see contents" use case.

---

### 3. Bot Agent (Mineflayer)

**How it works:** A headless Minecraft client (bot) joins the server as a separate player. Full JavaScript API for interacting with the world.

**Capabilities:**
- Complete world state access: blocks, entities, players, weather, time
- Inventory management: open chests, furnaces, crafting tables; read and manipulate contents
- Block interaction: dig, place, activate
- Entity interaction: attack, use items, ride vehicles
- Pathfinding (A* with plugins)
- Real-time entity tracking
- Chat and command execution

**Limitations:**
- It's a **separate player** — not the human player's session
- Uses a server slot (may conflict with player limits)
- Cannot observe the human player's GUI state (what screen they have open)
- Requires authentication (online-mode servers need a valid Minecraft account)
- Actions are performed by the bot, not on behalf of the human player

**Verdict:** Excellent for automated testing and world interaction. Less suitable for debugging a human player's mod experience in real-time.

---

### 4. Data Packs / Function Files

**How it works:** `.mcfunction` files containing command lists, loaded as data packs. Tick functions run every game tick; load functions run on world load.

**Capabilities:**
- Run complex command sequences automatically
- Scoreboard-based state tracking
- Advancement-triggered command chains (detect specific player achievements/actions)
- Can be reloaded with `/reload` without restarting

**Limitations:**
- **No event callbacks** for player interactions (opening chests, clicking blocks)
- Cannot detect GUI events
- Limited to what the command system exposes
- No structured data output — results go to chat or logs

**Verdict:** Useful as a supplementary tool (e.g., periodic state snapshots via tick functions) but insufficient as a primary interface.

---

### 5. Query Protocol

**How it works:** UDP-based protocol for server status queries.

**Capabilities:**
- Server metadata: MOTD, player count, player names, version, map name
- Basic and full stat modes

**Limitations:**
- Cached every 5 seconds
- **No game state data** — no inventories, blocks, entities, or world data
- Java Edition only

**Verdict:** Useless for this purpose. Only provides server listing information.

---

### 6. Log File Parsing

**How it works:** Tail/parse the server's `logs/latest.log` file.

**Capabilities:**
- Chat messages, command execution records, player join/leave
- Command output (when broadcast to console)
- Error messages and stack traces (useful for mod debugging!)

**Limitations:**
- Unstructured text, format varies
- Polling-based (not real-time)
- Fragile — log format changes between versions
- Only contains what the server chooses to log

**Verdict:** Useful as a supplementary source, especially for capturing mod errors/stack traces. Not suitable as a primary interface.

---

### 7. Debug Screen (F3)

**How it works:** In-game overlay showing coordinates, biome, block data, performance metrics.

**Limitations:**
- **Not accessible programmatically** from outside the game client
- Purely visual overlay

**Verdict:** Not usable.

---

## Capability Matrix

| Capability | RCON+/data | Proxy | Bot | Data Pack | Query | Logs |
|---|---|---|---|---|---|---|
| Read player inventory | Yes (text) | Yes (structured) | Own only | No | No | No |
| Read chest contents | Yes (text) | Yes (structured) | Yes (API) | No | No | No |
| Detect chest opening | No | **Yes** | Own only | No | No | No |
| Read entity data | Yes (text) | Yes (structured) | Yes (API) | Partial | No | No |
| Execute commands | Yes | Yes (inject) | Yes (chat) | Yes (auto) | No | No |
| Event-driven | No (poll) | **Yes** | Partial | Partial (tick) | No | No (poll) |
| Structured responses | No | **Yes** | **Yes** | No | No | No |
| No client changes | Yes | No (proxy) | Yes | Yes | Yes | Yes |
| Singleplayer support | No* | No* | No* | Yes | No | Partial |
| Mod internal state | No | No | No | No | No | Partial (logs) |

*Requires LAN or server mode

---

## Critical Gap: What Still Requires a Mod

None of these approaches can:

1. **Inspect mod-internal state** — variable values, object states, registry contents
2. **Hook into mod events** — custom event handlers, mod-specific callbacks
3. **Parse custom GUI elements** — mod-added screens with custom behavior beyond vanilla container types
4. **Set breakpoints or step through mod code** — runtime debugging
5. **Access custom mod registries** — items, blocks, entities registered by mods
6. **Observe mod-to-mod interactions** — how mods communicate internally

For the stated goal of **"automating the debugging of minecraft mods,"** these are significant gaps.

---

## Recommended Architecture

### Hybrid: Protocol Proxy + Lightweight Mod

```
[AI/MCP Client] <-> [MCP Server (Rust)]
                        |
                        ├── [Protocol Proxy Layer]
                        |       Intercepts all vanilla packets
                        |       Provides event-driven game state observation
                        |       Structured data (no text parsing)
                        |
                        ├── [RCON Connection]
                        |       Command execution
                        |       /data queries for server-side state
                        |
                        └── [Mod Plugin Channel] (optional)
                                Custom packet for mod introspection
                                Registry dumps, internal state queries
                                Only needed for deep mod debugging
```

**Why this works:**
- The proxy handles 80% of use cases (game state observation, event detection, container contents) with zero mod dependency
- RCON provides command execution without any client-side changes
- The mod becomes **optional** — only needed when debugging mod internals specifically
- The mod can be extremely lightweight (just a custom packet handler that exposes internal state on request)

### Pure No-Mod Alternative

If a mod is truly unacceptable:

```
[AI/MCP Client] <-> [MCP Server (Rust)]
                        |
                        ├── [Protocol Proxy] — game state + events
                        ├── [RCON] — command execution + /data queries
                        └── [Log Watcher] — error/crash capture
```

This covers:
- Opening a chest → proxy detects `open_window` + `window_items` packets → returns container contents
- Placing a block → proxy detects block change packets → confirms placement
- Reading entity data → RCON `/data get entity` → parsed text response
- Detecting mod errors → log watcher catches stack traces

This does NOT cover:
- "Why is my mod's custom GUI not rendering correctly?"
- "What value does my mod's config manager have at runtime?"
- "Which event handlers are my mod registering?"

---

## Final Verdict

**A mod is not required for the core call-and-response MCP pattern.** A protocol proxy + RCON combination provides rich, structured, event-driven access to vanilla Minecraft game state. This is sufficient for:
- Functional testing of mods (do they produce the right blocks/items/entities?)
- Observing game state changes in response to actions
- Automated interaction with the game world

**A mod IS required for deep mod debugging** — inspecting internal state, custom registries, and mod-specific behavior beyond what's visible in the vanilla protocol.

**Recommendation:** Build the MCP server with a proxy-first architecture. Make the mod an optional enhancement, not a requirement. This reduces the barrier to entry and provides immediate value even without mod installation.
