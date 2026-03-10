# Survey of Existing Minecraft Programmatic Interaction Projects

**Author:** explorer-existing (adversarial explorer)
**Date:** 2026-03-10
**Purpose:** Comprehensive survey of existing projects that provide programmatic Minecraft interaction, evaluated against our use case: an MCP server for mod debugging via Claude Code.

---

## Our Use Case Requirements

For reference, the proposed system needs:
1. **In-game mod interaction** — read GUI screens, chest contents, mod-specific state
2. **Call-and-response** — issue a command, get structured response with consequences
3. **Dual loader support** — Fabric and NeoForge on latest Minecraft
4. **Debugging focus** — inspect mod internals, error states, log output
5. **MCP protocol** — expose tools via Model Context Protocol for Claude Code

---

## Category 1: Protocol-Based External Clients

### Mineflayer (PrismarineJS)
- **URL:** github.com/PrismarineJS/mineflayer
- **Language:** JavaScript/Node.js (also accessible from Python)
- **Stars:** 6,700 | **Activity:** Very active, 2,889 commits
- **How it works:** Connects directly via Minecraft network protocol as a separate bot client. No mods required.
- **Capabilities:** Entity tracking, inventory management, chest/furnace interaction, block manipulation, chat, crafting, physics/movement
- **Version support:** MC 1.8 – 1.21.11
- **Limitations for our use case:**
  - Connects as a **separate player/bot**, not from within the user's game session
  - Only understands **vanilla protocol** — cannot see mod-specific GUIs, custom screens, or modded packets
  - Cannot inspect mod internals or debug mod state
  - Version-fragile when protocol changes
- **Adaptability:** LOW. Fundamentally wrong architecture for mod debugging. Would need to reverse-engineer every mod's custom packets.

### node-minecraft-protocol (PrismarineJS)
- **URL:** github.com/PrismarineJS/node-minecraft-protocol
- **Language:** JavaScript/Node.js
- **Stars:** 1,400 | **Activity:** Very active, 1,601 commits
- **How it works:** Low-level Minecraft protocol parser/serializer. Foundation that Mineflayer builds on.
- **Capabilities:** Packet parsing, authentication, encryption, compression, server creation
- **Version support:** MC 1.7.10 – 1.21.11
- **Limitations:** Same as Mineflayer — external client, vanilla protocol only
- **Adaptability:** LOW. Same architectural mismatch.

---

## Category 2: In-Game Mods (Navigation/Automation)

### Baritone
- **URL:** github.com/cabaletta/baritone
- **Language:** Java
- **Stars:** 8,700 | **Activity:** Very active, 85 releases
- **How it works:** Minecraft mod for autonomous pathfinding and mining. Chat command interface.
- **Mod loaders:** Forge, Fabric, NeoForge
- **Version support:** MC 1.12.2 – 1.21.8
- **Capabilities:** Pathfinding, mining automation, elytra flight, goal-based navigation
- **Limitations:** Purely movement/mining focused. No general-purpose API, no mod debugging.
- **Adaptability:** NONE. Wrong domain entirely. But its multi-loader support is a useful reference.

---

## Category 3: In-Game Scripting Mods

### Minescript
- **URL:** github.com/maxuser0/minescript
- **Language:** Python scripts, Java mod
- **Stars:** 191 | **Activity:** Active, 644 commits
- **Mod loaders:** Fabric, Forge, NeoForge
- **How it works:** Mod loads Python scripts from `minescript/` folder, executed via chat commands. Provides `minescript.py` library.
- **Capabilities:** Player position/inventory, block queries, command execution, chat messages
- **Limitations:** Async scripting model (not call-and-response). No GUI interaction documented. Scripts are fire-and-forget.
- **Adaptability:** MEDIUM. Cross-loader Python scripting inside Minecraft is interesting. Could potentially serve as a bridge layer if adapted to listen for MCP commands. But would need significant architectural changes for bidirectional call-and-response.

### KubeJS
- **URL:** github.com/KubeJS-Mods/KubeJS
- **Language:** JavaScript (Rhino engine), Java mod
- **Stars:** 430 | **Activity:** Active, 2,430 commits
- **Mod loaders:** Primarily NeoForge
- **How it works:** Event-driven JavaScript scripting. Scripts modify recipes, world gen, add custom blocks/items.
- **Capabilities:** Event handling, recipe modification, content creation, Java class access
- **Limitations:** Designed for pack development, not runtime debugging. Scripts run at load time. Not interactive.
- **Adaptability:** LOW. Wrong paradigm — it's for content authoring, not runtime inspection.

### CraftTweaker
- **URL:** github.com/CraftTweaker/CraftTweaker
- **Language:** ZenScript, Java
- **Stars:** N/A | **Activity:** Active, 3,093 commits
- **Mod loaders:** Fabric, NeoForge, Forge
- **How it works:** ZenScript-based customization of recipes, events, commands
- **Capabilities:** Recipe modification, event scripting, command creation
- **Limitations:** Same as KubeJS — pack customization, not debugging
- **Adaptability:** LOW. Not designed for runtime interaction.

### Carpet Mod (Scarpet)
- **URL:** github.com/gnembon/fabric-carpet
- **Language:** Scarpet (custom language), Java
- **Stars:** N/A | **Activity:** Active, 186 releases, 82 contributors
- **Mod loaders:** Fabric only
- **How it works:** Scarpet scripting language with app-store model for community scripts
- **Capabilities:** Server monitoring (TPS, mobcap), farm counters, block manipulation
- **Limitations:** Fabric-only. Focused on vanilla technical Minecraft. Custom scripting language.
- **Adaptability:** LOW. Fabric-only and wrong focus area.

---

## Category 4: HTTP/API Interface Mods

### GDMC HTTP Interface
- **URL:** github.com/Niels-NTG/gdmc_http_interface
- **Language:** Java
- **Stars:** N/A | **Activity:** Active, 499 commits, 38 releases
- **Mod loaders:** Fabric AND NeoForge (single JAR!)
- **Version support:** MC 1.16.5 – 1.21.11
- **How it works:** Mod runs HTTP server on localhost:9000. REST API with 14 endpoints.
- **Capabilities:** Block read/write, biome/chunk data, NBT structures, entity management, player info, command execution, heightmaps
- **API design:** True call-and-response REST API (GET, POST, PUT, PATCH, DELETE)
- **Limitations:** Focused on world generation/editing (for GDMC competition). No GUI interaction, no mod-specific debugging.
- **Adaptability:** HIGH (architecturally). **This is the strongest architectural precedent.** Proves that:
  - In-game HTTP server works reliably for call-and-response
  - Dual Fabric + NeoForge from a single JAR is achievable
  - External tools can interact with game state via HTTP
  - The approach scales to 14+ endpoints without issues

---

## Category 5: AI/Research Platforms

### Microsoft Project Malmo
- **URL:** github.com/microsoft/malmo
- **Language:** Python, Java, C++
- **Stars:** N/A | **Activity:** DEAD — last release Nov 2018
- **How it works:** XML mission definitions, gym-like API for RL agents
- **Limitations:** Completely obsolete. Ancient Minecraft version. No longer maintained.
- **Adaptability:** NONE. Historical interest only.

---

## Category 6: Existing MCP (Model Context Protocol) Servers for Minecraft

### yuniko-software/minecraft-mcp-server (499 stars)
- **Language:** TypeScript
- **Architecture:** Mineflayer-based bot + MCP protocol
- **Capabilities:** Movement, inventory, blocks, chat, furnace, entity interaction
- **Limitations:** External bot (not in-game). No mod awareness. No GUI interaction. Version-fragile.
- **Relevance:** Demonstrates MCP+Minecraft integration works, but wrong architecture for mod debugging.

### cuspymd/mcp-server-mod (5 stars)
- **Language:** Java (Fabric mod)
- **Architecture:** Fabric mod running HTTP MCP server on localhost:8080
- **Tools:** execute_commands, get_player_info, get_blocks_in_area, take_screenshot
- **Limitations:** Only 4 tools. No GUI interaction. No chest access. No NeoForge. Command whitelist is restrictive.
- **Relevance:** **CLOSEST existing project to our use case.** Proves Fabric+HTTP+MCP architecture works. But very limited scope — we'd need 10x more capabilities.

### MCDxAI/minecraft-dev-mcp (3 stars)
- **Language:** TypeScript
- **Architecture:** Standalone MCP server (not in-game)
- **Capabilities:** Decompilation, mapping systems (Yarn, Mojmap), mixin validation, mod JAR analysis, version diffing
- **Limitations:** Does NOT interact with a running game. Development tooling only.
- **Relevance:** Complementary to our project. Could be used alongside our server (they handle static analysis, we handle runtime interaction).

### gerred/mcpmc (38 stars)
- **Language:** TypeScript
- **Architecture:** Mineflayer-based bot + MCP via stdio
- **Relevance:** Same pattern as yuniko-software. External bot, not in-game.

### OGMatrix/mcmodding-mcp (14 stars)
- **Language:** TypeScript
- **Capabilities:** Provides access to Minecraft modding documentation
- **Relevance:** Documentation access, not game interaction.

### Others (< 10 stars each)
- **Peterson047/Minecraft-MCP-Server** — RCON-based server management
- **center2055/MinecraftDeveloperMCP** — Spigot/Paper server control
- **joshdevous/minecraft-builder-claude-mcp-server** — WorldEdit structure generation
- **Asintotoo/PaperMCP** — Paper server MCP
- **Zhou-Shilin/mcp-server-mod-devdoc** — Modding documentation

---

## Comparative Matrix

| Project | In-Game? | GUI Access | Mod-Aware | Call/Response | Fabric | NeoForge | Active | Stars |
|---------|----------|------------|-----------|---------------|--------|----------|--------|-------|
| Mineflayer | No (ext. client) | No | No | Yes | N/A | N/A | Yes | 6.7k |
| Baritone | Yes (mod) | No | No | Partial | Yes | Yes | Yes | 8.7k |
| Minescript | Yes (mod) | No | Partial | No (async) | Yes | Yes | Yes | 191 |
| KubeJS | Yes (mod) | No | Partial | No (events) | No | Yes | Yes | 430 |
| CraftTweaker | Yes (mod) | No | Partial | No (scripts) | Yes | Yes | Yes | N/A |
| Carpet/Scarpet | Yes (mod) | No | No | Partial | Yes | No | Yes | N/A |
| GDMC HTTP | Yes (mod) | No | No | **Yes (REST)** | **Yes** | **Yes** | Yes | N/A |
| Project Malmo | Yes (mod) | Partial | No | Yes | No | No | **Dead** | N/A |
| cuspymd MCP mod | Yes (mod) | No | No | **Yes (HTTP)** | **Yes** | No | Yes | 5 |
| yuniko MCP server | No (ext. bot) | No | No | Yes (MCP) | N/A | N/A | Yes | 499 |
| MCDxAI dev MCP | No (standalone) | No | **Yes** | Yes (MCP) | N/A | N/A | Yes | 3 |
| **Our Proposed** | **Yes (mod)** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | — | — |

---

## Key Findings

### 1. No Direct Competitor Exists
No existing project combines in-game mod interaction (GUI reading, chest inspection, error state detection) with MCP protocol support. Our proposed project fills a genuine gap.

### 2. Architecture is Validated
- **GDMC HTTP Interface** proves in-game HTTP server with dual Fabric+NeoForge support works (single JAR, localhost server, REST API with 14+ endpoints)
- **cuspymd/mcp-server-mod** proves Fabric+HTTP+MCP integration works (just needs more tools and NeoForge support)
- Both demonstrate the call-and-response pattern is reliable

### 3. Protocol-Based Approaches Are Insufficient
Mineflayer and node-minecraft-protocol are powerful but fundamentally wrong for mod debugging — they can't see mod internals, custom GUIs, or modded game state.

### 4. Scripting Mods Are Wrong Paradigm
KubeJS, CraftTweaker, and Scarpet are designed for content authoring/pack development, not runtime debugging interaction.

### 5. Complementary Projects Exist
MCDxAI/minecraft-dev-mcp handles static analysis (decompilation, mappings, mixin validation) and could complement our runtime interaction server.

### 6. Risks to Consider
- **Version churn**: Minecraft updates frequently. Every project in this survey deals with version compatibility pain.
- **Mod loader fragmentation**: Supporting both Fabric and NeoForge requires either a shared codebase with platform-specific adapters (like GDMC) or separate builds.
- **GUI interaction is novel**: No existing project successfully reads arbitrary mod GUIs. This is the hardest unsolved problem and our key differentiator.

---

## Recommendation (Adversarial Perspective)

As the adversarial explorer, I must note: **the gap exists for a reason**. GUI interaction and mod-state inspection are genuinely hard problems because:

1. Minecraft's GUI system is not designed for external consumption
2. Mod GUIs are wildly inconsistent in implementation
3. Screen/container data structures vary across mods
4. The Fabric and NeoForge APIs for screen access differ significantly

However, the architecture IS proven (GDMC and cuspymd validate the approach), and a well-scoped initial version focusing on vanilla GUI interaction + command execution could be very valuable even without universal mod GUI support.

**Suggested approach based on survey:**
- Start with GDMC-style architecture (in-game HTTP server, dual loader)
- Add MCP protocol layer (like cuspymd but more comprehensive)
- Begin with vanilla GUI/container interaction before tackling mod-specific screens
- Consider integration with MCDxAI/minecraft-dev-mcp for static analysis capabilities
