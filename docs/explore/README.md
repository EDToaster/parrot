# Minecraft MCP Server - Exploration Reports

Feasibility exploration and design refinement conducted 2026-03-10 by 13 parallel explorer agents + 1 evaluator.

## Reports

### Core Feasibility
1. [Fabric API Feasibility](01-fabric-feasibility.md) — Screen/GUI, block interaction, entity, world state, events
2. [NeoForge API Feasibility](02-neoforge-feasibility.md) — Event bus, screen events, container access, world APIs
3. [Cross-Loader Architecture](03-crossloader-architecture.md) — MultiLoader Template vs Architectury vs alternatives
4. [Embedded WebSocket Server](04-websocket-server.md) — Netty reuse, thread safety, classloading, security

### Adversarial Challenges
5. [Do We Need a Mod?](05-adversarial-no-mod.md) — RCON, protocol proxy, data packs, log parsing
6. [Existing Solutions Survey](06-adversarial-existing-solutions.md) — 15+ projects evaluated, gap confirmed
7. [Scripting Engine Approach](07-adversarial-scripting-engine.md) — Rhino/Lua/GraalJS, hybrid API recommendation
8. [Client Automation](08-adversarial-client-automation.md) — Screenshot/OCR/input simulation analysis

### Synthesis
9. [Evaluation Report](09-evaluation-report.md) — Comparative evaluation and final architecture recommendation

### Refinement (Detailed Design)
10. [Kotlin MCP Server Design](10-mcp-server-design.md) — SDK, stdio transport, WebSocket client, tool registration, connection discovery
11. [Client/Server Side Split](11-client-server-design.md) — GUI (client) vs world state (server), singleplayer vs dedicated, packet relay
12. [Protocol Design](12-protocol-design.md) — Playwright-style action-consequence protocol, tick-based windows, JSON examples
13. [Tool Vocabulary](13-tool-vocabulary.md) — 21 MCP tools across 9 categories, 6 resources, debugging workflow examples
14. [Build & Distribution](14-build-distribution-design.md) — Gradle multi-module, Shadow JAR, installation, CI/CD

## Key Conclusions

- **Verdict:** Build it. Both Fabric and NeoForge fully support all required capabilities.
- **Architecture:** MultiLoader Template, Netty WebSocket, command queue + tick handler
- **Gap confirmed:** No existing project combines in-game mod interaction + GUI reading + MCP protocol
- **Adversarial outcomes:** Mod is necessary; scripting escape hatch is a strong post-MVP feature
