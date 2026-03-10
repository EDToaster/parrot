# Evaluation Report: Minecraft MCP Server Feasibility

**Evaluator:** evaluator
**Date:** 2026-03-10
**Reports Reviewed:** 8 explorer reports (4 primary, 4 adversarial)

---

## Executive Summary

**Verdict: BUILD IT.** All 8 explorers confirm that a mod-based MCP server for Minecraft is highly feasible. Both Fabric and NeoForge provide comprehensive APIs for every required capability. The architecture is validated by existing projects (GDMC HTTP Interface, cuspymd/mcp-server-mod). No fundamental technical blockers were identified. The adversarial challenges strengthen rather than undermine the recommendation — they confirm a mod is necessary and that no existing project fills this gap.

**Recommended architecture:** Java mod using the MultiLoader Template (common/fabric/neoforge), embedded Netty WebSocket server, JSON command/response protocol, command queue + tick handler for thread safety. MCP server in Rust or TypeScript as a separate process connecting via WebSocket.

---

## 1. Consensus (Where All Explorers Agree)

### 1.1 A Mod Is Necessary
Every explorer — including adversarial ones — agrees that a mod is required for the core use case of debugging Minecraft mods. The no-mod explorer (explorer-no-mod) found that protocol proxies and RCON cover ~80% of *vanilla* interaction, but cannot access mod internals, custom GUIs, or mod registries. The client automation explorer (explorer-automation) found external automation captures only 5-10% of needed data at 20-100x worse latency. **A mod is not optional.**

### 1.2 Both Fabric and NeoForge Are Fully Capable
The Fabric and NeoForge explorers independently confirmed that both loaders provide:
- **Screen/GUI interception:** Fabric's `ScreenEvents` and NeoForge's `ScreenEvent.*` both offer full lifecycle hooks (open, close, init, render, tick, input)
- **Container/inventory reading:** Both provide `containerMenu.slots` iteration for reading ItemStack data
- **Block interaction:** Both support programmatic block right-click/left-click via interaction managers
- **Entity inspection:** Both expose full entity queries, health, equipment, NBT serialization
- **World state:** Both provide complete read access to blocks, biomes, light, time, weather
- **Event systems:** Both have rich event buses covering lifecycle, ticking, interaction, screens

Neither loader has a significant capability gap for this project.

### 1.3 Netty WebSocket Is the Right Communication Layer
All relevant explorers converge on using Minecraft's bundled Netty for the WebSocket server:
- **Zero additional dependencies** — Netty is already on the classpath
- **Battle-tested** in the Minecraft runtime environment
- **WebSocket APIs stable** since Netty 4.0 (current: 4.1.x)
- **Precedent:** Dynmap and BlueMap embed HTTP servers using similar patterns

### 1.4 Command Queue + Tick Handler Is the Right Threading Model
Every explorer that addressed threading converged on the same pattern:
1. WebSocket handler receives JSON command on Netty I/O thread
2. Command enqueued to `ConcurrentLinkedQueue`
3. Server tick handler drains queue on main game thread
4. Results returned via `CompletableFuture` back to WebSocket handler
5. JSON response sent to client

This is a well-established game development pattern. NeoForge's own `context.enqueueWork()` validates it.

### 1.5 No Direct Competitor Exists
The existing solutions explorer (explorer-existing) surveyed 15+ projects and found:
- **No project combines** in-game mod interaction + GUI reading + MCP protocol support
- Mineflayer/PrismarineJS: external bot, can't see mod internals
- KubeJS/CraftTweaker/Scarpet: content authoring, not runtime debugging
- cuspymd/mcp-server-mod: only 4 tools, no GUI, no NeoForge
- GDMC HTTP Interface: world gen focused, no GUI or debugging

**This project fills a genuine gap.**

---

## 2. Disagreements and Divergent Recommendations

### 2.1 Communication Protocol: WebSocket vs HTTP
- **WebSocket** (majority): Bidirectional, event-driven, supports push notifications for game events
- **HTTP** (explorer-existing, citing GDMC): Simpler, proven, no persistent connection needed

**My verdict:** WebSocket is correct. The event subscription use case (notify when a chest opens, an entity dies, a mod throws an exception) requires server-push capability. HTTP would require polling, which is wasteful and high-latency. WebSocket also aligns better with MCP protocol transport options.

### 2.2 Cross-Loader Strategy: MultiLoader Template vs Architectury
- **MultiLoader Template** (explorer-crossloader): Zero dependencies, clean separation, common code uses only vanilla APIs
- **Architectury** (mentioned by crossloader, scripting): More abstractions, 90+ unified events, but adds runtime dependency

**My verdict:** MultiLoader Template for MVP. The mod's common code (WebSocket server, protocol, command framework) is ~85-90% loader-agnostic pure Java. The remaining 10-15% (event registration, screen access, lifecycle hooks) is small enough to duplicate via interfaces. Migration to Architectury is possible later if needed, since both use the same common/fabric/neoforge layout.

### 2.3 Whether to Include a Scripting Engine
- **Yes** (explorer-scripting): Rhino/JavaScript escape hatch for AI-driven arbitrary inspection. AI doesn't know in advance what it needs to inspect.
- **Implicit no** (other explorers): Focused on structured API only.

**My verdict:** Structured API first, scripting escape hatch as a post-MVP feature. The scripting case is compelling — an AI debugging an unknown mod genuinely benefits from being able to write arbitrary inspection code. But it adds complexity (sandboxing, timeouts, security) that should not block the initial release. Plan the architecture to accommodate it, but don't build it in MVP.

### 2.4 Hybrid vs Pure Mod Architecture
- **Explorer-no-mod** suggests a proxy-first architecture with mod as optional enhancement
- **All other explorers** assume mod-first architecture

**My verdict:** Mod-first. The proxy approach is clever but adds complexity (player must connect through proxy, Node.js dependency for protocol parsing) and still can't access mod internals. For the stated goal of "automating the debugging of minecraft mods," the mod IS the product. A protocol proxy could be a complementary feature later.

---

## 3. Adversarial Challenge Assessment

### 3.1 No-Mod Approach (explorer-no-mod) — Strength: MODERATE
**Challenge:** A protocol proxy + RCON can handle the "open chest → see contents" use case without any mod.

**Impact on recommendation:** Does not change the recommendation. The no-mod approach is valid for vanilla game state observation, but the project's raison d'etre is mod debugging, which requires in-process access. The explorer itself acknowledges this: "A mod IS required for deep mod debugging."

**Useful takeaway:** The proxy approach could be a complementary layer for users who can't install the mod. Low priority.

### 3.2 Existing Solutions (explorer-existing) — Strength: HIGH
**Challenge:** Before building, survey what exists. If something already does this, don't reinvent.

**Impact on recommendation:** Strengthens the recommendation. The survey confirms no existing project fills this gap. GDMC HTTP Interface validates the architecture (in-game server, dual loader, REST API). cuspymd/mcp-server-mod validates MCP integration. But neither provides GUI reading, mod debugging, or comprehensive tooling.

**Useful takeaway:** GDMC's single-JAR dual-loader approach is worth studying. MCDxAI/minecraft-dev-mcp could be complementary for static analysis.

### 3.3 Scripting Engine (explorer-scripting) — Strength: MODERATE-HIGH
**Challenge:** A structured API is too rigid for AI debugging of unknown mods. AI needs to write arbitrary inspection code.

**Impact on recommendation:** Does not change the core architecture but adds a compelling post-MVP feature. The hybrid model (structured API + scripting escape hatch) is the right long-term target.

**Useful takeaway:** Rhino is the proven choice (KubeJS validates it). Plan the architecture so scripts can be added later — specifically, ensure the command executor is extensible.

### 3.4 Client Automation (explorer-automation) — Strength: LOW
**Challenge:** Can OS-level screenshot capture + input simulation replace a mod?

**Impact on recommendation:** None. The explorer itself concludes the approach is "fundamentally insufficient." External automation accesses 5-10% of needed data at 20-100x worse latency with unreliable input simulation. Minecraft's raw input mode may bypass simulated mouse events entirely.

**Useful takeaway:** Negligible. Visual verification of mod rendering is a theoretical nice-to-have but not worth building.

---

## 4. Recommended Final Architecture

### Language
- **Minecraft mod:** Java 21 (required by Minecraft 1.21.x)
- **MCP server:** Rust or TypeScript (user preference; connects via WebSocket)

### Mod Loader Strategy
- **MultiLoader Template** (jaredlll08/MultiLoader-Template)
- Project structure: `common/`, `fabric/`, `neoforge/`
- Common module: WebSocket server, JSON protocol, command framework, response serialization
- Loader modules: Mod entrypoints, event registration, lifecycle hooks, screen access adapters
- Define interfaces in common (`PlatformBridge`, `ScreenReader`, `EventSubscriber`), implement per-loader

### Communication Protocol
- **WebSocket** over localhost (default port: `25566`)
- **JSON** command/response format with request IDs for correlation
- **Bidirectional:** client sends commands, server sends responses + event notifications
- Bind to `127.0.0.1` by default; shared secret token for authentication

### Thread Safety
- Netty WebSocket server on dedicated `NioEventLoopGroup` (daemon threads)
- `ConcurrentLinkedQueue<Command>` for incoming commands
- Server tick handler drains queue, executes on main thread
- `CompletableFuture<Response>` for async response delivery
- Batch limit per tick to prevent lag

### API Style
- **Structured JSON command/response** (MVP)
- Commands: `get_block`, `get_entities`, `get_inventory`, `get_screen`, `interact_block`, `run_command`, `subscribe_event`
- Responses: Typed JSON with request ID correlation
- **Scripting escape hatch** (post-MVP): `execute_script` command with Rhino/JavaScript, sandboxed, time-limited

### Key Libraries
- **Netty** (bundled) — WebSocket server
- **Gson** (bundled) — JSON serialization
- No additional dependencies for MVP

---

## 5. Top Risks and Open Questions

| # | Risk | Severity | Mitigation |
|---|------|----------|------------|
| 1 | **Client/server side split** — Screen/GUI events are client-only; world state is server-side. Singleplayer has both; dedicated servers need a connected client. | Medium | Design mod with both client and server components. For dedicated servers, the mod runs on both sides. Document this clearly. |
| 2 | **Mod GUI diversity** — Each mod implements GUIs differently. Reading arbitrary mod GUIs is the hardest unsolved problem. | Medium | Start with vanilla container types (chests, furnaces, crafting). Add mod-specific screen adapters over time. The scripting escape hatch (post-MVP) helps bridge the gap. |
| 3 | **Minecraft version churn** — MC updates break mods. Both loaders update at different rates. | Medium | Minimize Mixin use. Depend on stable public APIs. Plan for staggered version support. |
| 4 | **Thread safety bugs** — Accessing game state from wrong thread causes crashes. | Medium | Strict queue pattern. Never access game state outside tick handler. Code review discipline. |
| 5 | **MCP protocol evolution** — The Model Context Protocol is still evolving. | Low | Implement a thin MCP adapter layer that can be updated independently of the mod. |

### Open Questions
1. **Should the MCP server be embedded in the mod or a separate process?** Recommendation: separate process (Rust/TS) connecting via WebSocket. This keeps the mod lightweight and allows the MCP server to be updated independently.
2. **How to handle dedicated server vs singleplayer?** The mod needs both client-side and server-side components. For singleplayer, both run in the same JVM. For dedicated servers, the client-side component may need to be a thin "probe" mod.
3. **Should we support Minecraft versions beyond the latest?** Not for MVP. Target 1.21.x only. Consider 1.20.x if demand exists.
4. **Port discovery:** How does the MCP server find the mod's WebSocket port? Options: fixed default, config file, or port written to a known file path on startup.

---

## 6. Recommended MVP Scope

### Phase 1: Core Infrastructure
- MultiLoader Template project setup (common/fabric/neoforge)
- Embedded Netty WebSocket server with token auth
- JSON command/response protocol with request IDs
- Command queue + tick handler thread safety pattern
- Mod lifecycle management (start/stop with server)

### Phase 2: Read-Only Game State
- `get_block(x, y, z)` — block state + block entity data
- `get_entities(x, y, z, radius)` — nearby entities with type, position, health, equipment
- `get_player_info()` — position, health, food, effects, inventory summary
- `get_world_info()` — time, weather, dimension, game rules
- `get_screen()` — currently open screen type + contents (vanilla containers)

### Phase 3: Interaction Commands
- `run_command(command)` — execute any Minecraft command
- `interact_block(x, y, z)` — simulate right-click on block
- `click_slot(slot)` — interact with open container

### Phase 4: Event Subscriptions
- `subscribe(event_type)` — push notifications for screen open/close, block break, entity death, chat messages, errors/exceptions

### Phase 5: MCP Protocol Wrapper
- Separate Rust or TypeScript MCP server process
- Translates MCP tool calls to mod WebSocket commands
- Exposes game state as MCP tools for Claude Code

### Out of MVP Scope (Future)
- Scripting escape hatch (Rhino/JavaScript)
- Mod-specific GUI adapters
- Protocol proxy for no-mod fallback
- Multi-instance support
- Custom breakpoints / conditional watches

---

## 7. Explorer Report Quality Assessment

| Explorer | Quality | Key Contribution |
|----------|---------|------------------|
| explorer-fabric | Excellent | Comprehensive API mapping with code examples |
| explorer-neoforge | Excellent | Thorough event bus and API documentation |
| explorer-crossloader | Excellent | Clear recommendation with 5 approaches evaluated and risk matrix |
| explorer-websocket | Excellent | Complete architecture with security, threading, and library evaluation |
| explorer-no-mod | Very Good | Honest adversarial assessment; found proxy is viable but insufficient |
| explorer-existing | Excellent | 15+ projects surveyed; identified GDMC as architectural precedent and confirmed gap |
| explorer-scripting | Very Good | Compelling hybrid architecture proposal; well-researched runtime comparison |
| explorer-automation | Good | Thorough debunking of external automation; correctly identifies it as fundamentally insufficient |

All explorers produced substantive, well-reasoned reports. The quality was uniformly high.

---

## 8. Final Recommendation

**Proceed to implementation.** The feasibility is confirmed from every angle:
- APIs exist for every required capability on both loaders
- The architecture is validated by existing projects
- No existing tool fills this gap
- The adversarial challenges have been addressed and none are blocking
- The MVP scope is well-defined and achievable

The key differentiator of this project — reading GUI state and mod internals via MCP — is technically achievable and genuinely novel. Build it.
