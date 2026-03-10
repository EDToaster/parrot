# Feasibility Report: Embedded Scripting Runtime for Minecraft MCP Server

**Explorer:** explorer-scripting (adversarial)
**Date:** 2026-03-10
**Verdict:** FEASIBLE — with caveats. Hybrid approach recommended.

---

## Executive Summary

Embedding a scripting runtime inside a Minecraft mod to receive and execute freeform scripts from an MCP server is **technically feasible and well-proven**. Four major mods already do this: KubeJS (Rhino/JavaScript), ComputerCraft (Lua), Carpet Mod (Scarpet/custom), and CraftTweaker (ZenScript/custom). The approach offers superior flexibility for AI-driven debugging compared to a structured API, but introduces security, thread safety, and performance challenges that must be explicitly designed for.

**The strongest recommendation is a hybrid architecture**: structured API for common operations + scripting escape hatch for advanced/debugging use cases.

---

## 1. Prior Art: Existing Scripting Mods

### KubeJS (JavaScript via Rhino fork)
- **Engine:** Custom fork of Mozilla Rhino — compiles JavaScript to Java classes at runtime
- **Minecraft access:** Direct Java class access via `Java.loadClass()`, wrappers around MC classes
- **Mod loader support:** Both Fabric and NeoForge via Architectury
- **Security:** Class filtering via `kubejs.classfilter.txt` (allowlist/denylist of Java packages)
- **Maturity:** Most widely used scripting mod, large ecosystem, 727+ documented code snippets
- **Relevance:** Demonstrates that an external entity (script file → could be MCP server) can drive Minecraft behavior through JavaScript with full access to internals

### Carpet Mod / Scarpet (Custom DSL)
- **Engine:** Custom interpreter built into the mod
- **Threading:** Explicit threading primitives (`task`, `task_join`, `task_dock`, `synchronize`, `without_updates`)
- **Minecraft access:** Purpose-built functions for blocks, entities, players, world state, structures
- **Event system:** Tick-based callbacks (`__on_tick`, `__on_player_connects`, etc.)
- **Relevance:** Demonstrates that thread-safe scripting with Minecraft game loop integration is solvable. Scarpet's `task_dock` (synchronize with server thread) is exactly the pattern needed for MCP.

### ComputerCraft / CC:Tweaked (Lua)
- **Engine:** Custom/modified Lua interpreter (70% Java, 23% Lua codebase)
- **Isolation:** Each "computer" runs in its own execution context with time-slicing
- **Minecraft access:** Peripheral API — indirect, sandboxed access to the world
- **Relevance:** Demonstrates per-request isolated execution contexts, important for MCP where each command should be independent. Also shows the "indirect API" approach as a middle ground.

### CraftTweaker (ZenScript)
- **Engine:** ZenScript — custom language compiled on the JVM
- **Scope:** Primarily recipes, events, item properties
- **Relevance:** Shows that custom language engineering is possible but expensive. Less relevant for our use case since ZenScript is narrow in scope.

---

## 2. JVM Scripting Runtimes Evaluated

| Runtime | Performance | Java Interop | Sandboxing | Footprint | Minecraft Proven | Recommendation |
|---------|-------------|--------------|------------|-----------|------------------|----------------|
| **GraalJS** | Excellent (JIT) | Excellent (Java.type) | Good (allowHostAccess) | Heavy (needs GraalVM or polyglot deps) | No | Best if GraalVM dependency acceptable |
| **Rhino** | Moderate (interpreted/compiled) | Good (Java.loadClass) | Moderate (modular security) | Light | Yes (KubeJS) | **Best overall choice** — proven in Minecraft |
| **Luaj** | Good (bytecode compiler) | Good (Luajava library) | Good (library restriction, classloader isolation) | Very light | Yes (ComputerCraft) | Best for sandboxed execution |
| **Groovy** | Excellent (compiles to bytecode) | Excellent (native JVM) | Poor (hard to restrict) | Heavy | No | Not recommended — too hard to sandbox |

### Recommended Runtime: **Rhino** (primary) or **Luaj** (alternative)

**Rhino** is the safest choice because:
1. KubeJS has battle-tested it with millions of Minecraft installations
2. JavaScript is more familiar to AI models than Lua
3. `Java.loadClass()` gives full access to Minecraft internals when needed
4. Modular design allows stripping dangerous capabilities
5. Runs on standard JVM — no GraalVM dependency

**Luaj** is a strong alternative if stronger sandboxing is prioritized:
1. ComputerCraft has proven it works in Minecraft
2. Per-thread isolation via separate Globals instances
3. Class loader isolation for security boundaries
4. Lighter footprint than Rhino

---

## 3. Architecture: How Scripting Would Work

```
MCP Server (Rust)          Minecraft Mod (Java)
     │                           │
     │  WebSocket/TCP            │
     │  ─── send script ──────> │
     │                           │── Parse script
     │                           │── Queue for server thread
     │                           │── Execute on next tick
     │                           │── Capture return value
     │  <── return result ────── │
     │                           │
```

### Key Design Decisions

1. **Script execution must happen on the server thread** (or synchronize with it). Minecraft's world state is not thread-safe. KubeJS runs scripts during events on the server thread. Scarpet uses `task_dock` for server synchronization. CC:Tweaked uses a peripheral API that queues operations.

2. **Scripts should be stateless by default.** Each MCP request sends a complete script. No persistent script state between requests (avoids memory leaks, simplifies error recovery).

3. **Return values must be serializable.** Scripts return JSON-compatible results back to the MCP server. This means wrapping Minecraft objects in serialization helpers.

4. **Timeout enforcement is mandatory.** A script that infinite-loops will freeze the server. Must implement tick-counting or wall-clock timeouts (Scarpet and CC:Tweaked both do this).

---

## 4. Security Analysis

### Threat Model
The MCP server is controlled by the AI/user, not arbitrary third parties. This changes the threat model significantly — we're not protecting against malicious actors, but against **accidental damage** from AI-generated scripts.

### Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Infinite loop freezes server | High | Tick/time budget per script execution |
| Script crashes JVM | High | Try-catch wrapper, class access filtering |
| Script modifies files on disk | Medium | Sandbox: no java.io, no java.nio access |
| Script accesses network | Medium | Sandbox: no java.net access |
| Script corrupts world data | Medium | Execute on server thread, optional rollback |
| Memory exhaustion | Medium | Object allocation limits, script size limits |
| AI generates unsafe code | Low-Med | Structured API for destructive ops, scripting for read-only inspection |

### Recommended Security Layers
1. **Class allowlist** — only permit access to Minecraft classes, not java.io/java.net/java.lang.Runtime
2. **Execution timeout** — kill scripts after N ticks or M milliseconds
3. **Memory limits** — cap object allocations per script
4. **Read-only mode default** — scripts can inspect but not modify unless explicitly permitted
5. **Operation logging** — record all executed scripts for debugging

---

## 5. Performance Considerations

### Script Execution Overhead
- Rhino interpreted mode: ~10-50x slower than native Java for compute-heavy operations
- Rhino compiled mode: ~3-10x slower than native Java
- For our use case (inspect a chest, read some state): **performance is not a concern**. These are I/O-bound operations, not compute-bound.

### Game Loop Impact
- Scripts must execute within a single tick (50ms budget at 20 TPS)
- Simple inspection scripts (read block, check inventory): < 1ms
- Complex scripts (scan area, aggregate data): may need to be chunked across ticks
- Scarpet's approach of explicit async tasks is a good model

### Memory Impact
- Rhino context: ~1-5MB per execution context
- Script objects (wrapping MC objects): minimal if references are short-lived
- Key: destroy execution context after each script completes

---

## 6. Scripting vs Structured API: The Adversarial Case

### Why Scripting is BETTER for the MCP Use Case

1. **AI flexibility:** An AI debugging a mod doesn't know in advance what it needs to inspect. A structured API requires predefined commands. Scripting lets the AI compose arbitrary inspection logic.

2. **No API evolution problem:** Adding a new capability to a structured API requires mod updates. With scripting, the AI just writes new scripts.

3. **Compound operations:** "Open the chest at (10, 64, -5), check if it has diamonds, and if so, tell me the NBT data of each diamond stack" — this is one script vs. three structured API calls with intermediate coordination.

4. **Debugging power:** Scripts can hook into events, set conditional breakpoints, log state changes — things that are impractical with a fixed API.

5. **Proven:** KubeJS users already do this daily — write scripts that interact with arbitrary mod internals.

### Why Scripting is WORSE

1. **Unpredictable errors:** AI-generated scripts can fail in novel ways that are hard to diagnose over MCP.

2. **Security surface:** Even with sandboxing, the attack surface is much larger than a structured API.

3. **Mod loader divergence:** Script accessing `net.minecraft.world.item.ItemStack` works, but NeoForge/Fabric may expose different APIs at higher levels. Scripts need to be loader-aware.

4. **Brittleness across MC versions:** Internal class names change between Minecraft versions. Scripts that reference specific classes break on updates.

5. **No discoverability:** A structured API is self-documenting. Scripts require the AI to already know what classes/methods exist.

### Verdict: HYBRID

The optimal architecture is **both**:

```
MCP Server
    │
    ├── Structured API (90% of use cases)
    │   ├── inspectBlock(x, y, z)
    │   ├── getInventory(target)
    │   ├── getEntityData(selector)
    │   ├── executeCommand(cmd)
    │   └── subscribeEvent(type)
    │
    └── Script Escape Hatch (10% of use cases)
        └── executeScript(language, code, timeout)
            ├── Full Minecraft internal access
            ├── Sandboxed (class allowlist)
            ├── Time-limited
            └── Returns JSON result
```

The structured API handles common operations safely and predictably. The scripting escape hatch handles edge cases, advanced debugging, and novel inspection tasks. The AI can start with structured commands and fall back to scripting when the API doesn't cover what it needs.

---

## 7. Implementation Complexity Estimate

| Component | Complexity | Notes |
|-----------|-----------|-------|
| Embed Rhino in mod | Low | KubeJS's approach is well-documented, Rhino is a single Maven dependency |
| Class allowlist/sandbox | Medium | Need to define safe class set, test edge cases |
| Server thread synchronization | Medium | Queue scripts, execute on tick, return futures |
| Result serialization | Medium | Convert MC objects to JSON-safe representations |
| Timeout enforcement | Low-Medium | Rhino supports instruction counting |
| Cross-loader support | Medium | Fabric vs NeoForge class names differ; need abstraction layer or use Architectury |
| Script context management | Low | Create per-request, destroy after execution |

**Total: Medium complexity.** The hardest parts are sandboxing and cross-loader support, both of which are solved problems (KubeJS does both).

---

## 8. Recommendations

1. **Use Rhino as the scripting engine** — proven in Minecraft via KubeJS, lightweight, sandboxable.
2. **Build the hybrid architecture** — structured API primary, scripting as escape hatch.
3. **Execute scripts on the server thread** — use a queue + Future pattern.
4. **Implement class allowlisting** — permit Minecraft classes, block java.io/net/Runtime.
5. **Enforce execution timeouts** — Rhino instruction counting, kill after budget exceeded.
6. **Make scripts stateless** — each MCP request is independent, no persistent script state.
7. **Consider Luaj as alternative** if the user base prefers Lua or if stronger sandboxing is needed.
8. **Don't build a custom language** (like Scarpet/ZenScript) — too much engineering effort for this use case. Leverage existing runtimes.

---

## Appendix: Key References

- **KubeJS:** Uses Rhino fork, Architectury for cross-loader, class filtering for security
- **Scarpet:** Custom DSL with explicit threading (task_dock for server sync), tick-based events
- **CC:Tweaked:** Lua with per-computer isolation, peripheral API for world access
- **CraftTweaker:** ZenScript custom language, primarily recipe-focused
- **GraalJS:** Best performance but heavy GraalVM dependency
- **Rhino 1.9.1:** Current version, Java 11+, modular security
- **Luaj 3.0:** Lua 5.2, classloader isolation, per-thread Globals
