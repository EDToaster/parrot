# Cross-Loader Feasibility Report: Fabric + NeoForge Code Sharing

## Executive Summary

Supporting both Fabric and NeoForge from a single codebase is **highly feasible** for our MCP server mod. The majority of our code (networking server, protocol handling, command framework) is pure Java with no loader dependency. The loader-specific surface area is small and well-understood. We recommend **Architectury API** for projects needing event/networking abstractions, or the **MultiLoader Template** for a dependency-free approach.

---

## Approaches Evaluated

### 1. Architectury API (Recommended for feature-rich mods)

**What it is:** A toolchain comprising an API library, custom Loom fork, Gradle plugin, and IntelliJ plugin for multi-loader mod development.

**How it works:**
- Project is split into `common/`, `fabric/`, and `neoforge/` Gradle subprojects
- Common module contains loader-agnostic code
- `@ExpectPlatform` annotation enables static method injection — you declare a method in common, implementations live in loader-specific modules
- Provides 90+ event hooks abstracted across loaders
- Unified networking API (`NetworkManager`, `NetworkChannel`) for client-server communication
- Game registry abstraction for unified block/item/entity registration

**Strengths:**
- Most mature cross-loader solution in the ecosystem
- Active community (414 GitHub stars, 19 contributors, Discord)
- Abstracts the hardest differences (events, networking, registries)
- Supports Fabric + NeoForge + legacy Forge simultaneously
- Well-documented (828 code snippets in docs)

**Limitations:**
- `@ExpectPlatform` only works on static methods
- Adds a runtime dependency (players must install Architectury API)
- LGPL-3.0 license (though mods using it are unaffected)
- Architectury Loom is a fork of Fabric Loom — adds build complexity

**Verdict:** Best choice if the mod needs significant interaction with Minecraft events, networking, or registries.

---

### 2. MultiLoader Template (Recommended for our use case)

**What it is:** A Gradle project template by jaredlll08 that compiles a single codebase for multiple loaders without any API dependency.

**How it works:**
- `common/` module compiled against **vanilla Minecraft only** — no loader APIs accessible
- `fabric/`, `neoforge/`, `forge/` modules load common code and provide entrypoints
- Strict one-way dependency: common cannot access loader-specific code
- Loader modules can be added/removed by editing `settings.gradle`

**Strengths:**
- **Zero runtime dependencies** — no extra library needed
- Clean architectural separation enforced by build system
- Lightweight — no custom Loom fork or Gradle plugins
- Actively maintained (181 commits, multiple contributors)
- Removable loader support (drop a folder + one line in settings.gradle)

**Limitations:**
- Common code can ONLY use vanilla Minecraft APIs
- Any loader-specific functionality must be duplicated or manually abstracted via interfaces
- Requires Java 21
- No Eclipse support
- No built-in event/networking abstraction (you write your own)

**Verdict:** Best choice for mods where most logic is loader-agnostic (like ours — a TCP/WebSocket server with command execution).

---

### 3. Sinytra Connector (Not recommended)

**What it is:** A runtime translation layer that allows Fabric mods to run on NeoForge.

**How it works:**
- Players install Connector + Forgified Fabric API on NeoForge
- Fabric mods are automatically translated at runtime
- Supports MC 1.21.1 (primary) and 1.20.1 (LTS)

**Why not for us:**
- This is a **player-facing tool**, not a development strategy
- Individual mod compatibility varies and is not guaranteed
- We want native support on both loaders, not translation
- Adds fragility — our mod's reliability is critical for debugging workflows

**Verdict:** Irrelevant for development. Useful context for understanding the ecosystem.

---

### 4. Separate Repositories (Create mod approach)

**What it is:** Maintain completely separate codebases for each loader.

**How major mods do it:**
- **Create:** NeoForge primary repo, Fabric port maintained by separate org (Fabricators-of-Create)
- Advantages: Full loader-specific optimization, no abstraction overhead
- Disadvantages: Double maintenance burden, features can drift between versions

**Verdict:** Overkill for our mod. Only makes sense for massive mods with deep loader integration.

---

### 5. JEI-style Custom Multi-Module (Advanced)

**What it is:** JEI uses a custom multi-module Gradle setup with fine-grained separation: Common, CommonApi, Fabric, FabricApi, NeoForge, NeoForgeApi, Core, Gui, Library.

**Verdict:** Impressive but over-engineered for our needs. Worth studying if the mod grows complex.

---

## Analysis: What's Loader-Specific in Our MCP Server Mod?

| Component | Loader-Specific? | Notes |
|-----------|-----------------|-------|
| TCP/WebSocket server | **No** | Pure Java networking, fully shareable |
| MCP protocol (JSON-RPC) | **No** | Pure Java serialization |
| Command framework | **No** | Core logic is vanilla |
| Mod initialization | **Yes** | Fabric: `ModInitializer`, NeoForge: `@Mod` + constructor |
| Event registration | **Yes** | Fabric: callback objects, NeoForge: `@SubscribeEvent` / event bus |
| Server lifecycle hooks | **Yes** | Starting/stopping the TCP server on world load/unload |
| Command registration | **Slightly** | Both use Brigadier, but registration hooks differ |
| GUI/Screen interaction | **Yes** | Reading inventory contents, interacting with screens — most divergent area |
| World/Entity access | **Mostly no** | Minecraft's own classes; accessed through different mappings but same API |

**Estimate: 85-90% of code can be shared in the common module.**

---

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| GUI/Screen API differences | Medium | Abstract behind interface; implement per-loader |
| Mapping differences (Yarn vs Mojmap) | Low | Both loaders now support Mojang mappings |
| Event hook differences | Low | Small surface area; Architectury abstracts this if needed |
| Build complexity | Low | MultiLoader Template is straightforward |
| Dependency on Architectury maintenance | Medium | Only if using Architectury; MultiLoader Template has no deps |
| Minecraft version updates | Medium | Both loaders update independently; may need staggered releases |

---

## Recommendation

**For the MCP server mod, use the MultiLoader Template approach:**

1. **Why:** Our mod is predominantly pure Java (TCP server, protocol handling, command framework). The loader-specific surface area is small (~10-15% of code) and well-defined.

2. **Architecture:**
   ```
   common/       — TCP/WebSocket server, MCP protocol, command framework, response serialization
   fabric/       — Fabric mod entrypoint, event registration, lifecycle hooks
   neoforge/     — NeoForge mod entrypoint, event registration, lifecycle hooks
   ```

3. **For loader-specific code:** Define Java interfaces in `common/` (e.g., `PlatformBridge`, `ScreenReader`, `CommandRegistrar`), implement them in each loader module, and inject via a static accessor pattern.

4. **If we later need more abstractions:** We can migrate to Architectury API without restructuring — Architectury uses the same `common/fabric/neoforge` layout.

5. **Avoid:** Sinytra Connector (runtime translation, not dev tool), separate repositories (unnecessary maintenance burden), JEI-style granular modules (over-engineered for our scope).

---

## References

- [Architectury Docs](https://docs.architectury.dev/)
- [Architectury API GitHub](https://github.com/architectury/architectury-api) — 414 stars, LGPL-3.0
- [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template) — 181 commits
- [Sinytra Connector](https://github.com/sinytra/Connector) — MIT, runtime translation layer
- [JEI GitHub](https://github.com/mezz/JustEnoughItems) — example of custom multi-module approach
- [Create GitHub](https://github.com/Creators-of-Create/Create) — example of separate-repo approach
