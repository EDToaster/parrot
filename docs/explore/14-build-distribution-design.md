# Build, Packaging & Distribution Design

**Explorer:** explorer-build
**Date:** 2026-03-10

---

## 1. Repository Structure

### Recommendation: Mono-Repo with Gradle Multi-Module

A single repository containing both the Minecraft mod (multi-loader) and the MCP server. This simplifies versioning, CI/CD, and development workflow.

```
minecraft-mcp/
├── settings.gradle.kts          # Root Gradle settings
├── build.gradle.kts             # Root build config (shared properties)
├── gradle.properties            # Mod metadata, versions
├── gradle/
│   └── libs.versions.toml       # Version catalog
│
├── mod/                         # Minecraft mod (MultiLoader Template)
│   ├── common/                  # Loader-agnostic code (~85-90%)
│   │   ├── build.gradle.kts
│   │   └── src/main/java/
│   │       └── dev/minecraftmcp/
│   │           ├── server/      # Netty WebSocket server
│   │           ├── protocol/    # JSON command/response types
│   │           ├── commands/    # Command framework
│   │           └── bridge/      # Platform abstraction interfaces
│   │
│   ├── fabric/                  # Fabric-specific entrypoint & events
│   │   ├── build.gradle.kts
│   │   └── src/main/java/
│   │       └── dev/minecraftmcp/fabric/
│   │
│   └── neoforge/                # NeoForge-specific entrypoint & events
│       ├── build.gradle.kts
│       └── src/main/java/
│           └── dev/minecraftmcp/neoforge/
│
├── mcp-server/                  # MCP server (Kotlin/JVM)
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── dev/minecraftmcp/mcp/
│           ├── Main.kt          # Entry point
│           ├── McpServer.kt     # MCP protocol handler (stdio)
│           └── MinecraftClient.kt # WebSocket client to mod
│
└── .github/
    └── workflows/
        ├── build.yml            # CI build + test
        └── release.yml          # Release artifacts
```

### Why Mono-Repo over Separate Repos

| Factor | Mono-Repo | Separate Repos |
|--------|-----------|----------------|
| Version coordination | Automatic — same commit | Manual — must tag both |
| Protocol changes | Single PR touches both sides | Two coordinated PRs |
| CI/CD | One pipeline, matrix build | Two pipelines |
| Dev workflow | `./gradlew` runs everything | Two terminal sessions |
| User confusion | One repo to clone/star | Which repo do I need? |

The mod and MCP server share the JSON protocol definition. Keeping them together prevents version drift.

---

## 2. Mod Build: MultiLoader Template

### Why MultiLoader Template (not Architectury, not Stonecraft)

The evaluation report recommends the **jaredlll08/MultiLoader-Template** approach:

- **Zero runtime dependencies** — no extra library for players to install
- **Clean separation** enforced by Gradle: `common/` can only use vanilla Minecraft APIs
- **Lightweight** — no custom Loom fork, no Gradle plugins beyond standard NeoGradle/Fabric Loom
- **Actively maintained** (181+ commits)
- **Migration path** — can adopt Architectury later since both use the same common/fabric/neoforge layout

**Alternative considered: Stonecraft (gg.meza.stonecraft)**
Stonecraft uses Stonecutter for multi-version + multi-loader from a single source set with conditional compilation comments. While clever, this approach:
- Uses comment-based conditional compilation (`/*? if fabric {*/`) which is fragile
- Less ecosystem adoption than MultiLoader Template
- Adds Stonecutter as a build dependency
- Better suited for mods targeting many MC versions (we only target latest)

### Gradle Configuration (Kotlin DSL)

**`mod/common/build.gradle.kts`:**
- Compiles against vanilla Minecraft (Mojang mappings)
- No loader-specific APIs available
- Contains: WebSocket server (Netty), JSON protocol (Gson), command framework, platform bridge interfaces

**`mod/fabric/build.gradle.kts`:**
- Uses Fabric Loom
- Depends on `common` project
- Outputs: `minecraft-mcp-fabric-{version}.jar`
- Metadata: `fabric.mod.json` with mod ID, entrypoint, dependencies

**`mod/neoforge/build.gradle.kts`:**
- Uses NeoGradle
- Depends on `common` project
- Outputs: `minecraft-mcp-neoforge-{version}.jar`
- Metadata: `neoforge.mods.toml` (or `META-INF/neoforge.mods.toml`)

### Version Properties (`gradle.properties`)

```properties
# Minecraft
minecraft_version=1.21.4
# Fabric
fabric_loader_version=0.16.x
fabric_api_version=0.x.x
# NeoForge
neoforge_version=21.4.x
# Mod
mod_id=minecraft-mcp
mod_version=0.1.0
mod_group=dev.minecraftmcp
# MCP Server
mcp_server_version=0.1.0
```

---

## 3. MCP Server Build: Kotlin Shadow JAR

### Why Kotlin (not Rust, not TypeScript)

The evaluation report suggested Rust or TypeScript. However, **Kotlin on the JVM** is the better choice for this mono-repo:

| Factor | Kotlin/JVM | Rust | TypeScript |
|--------|-----------|------|------------|
| Same build system (Gradle) | Yes | No (Cargo) | No (npm) |
| Share protocol types with mod | Yes (same JVM) | No (FFI or duplicate) | No (duplicate) |
| User runtime requirement | JRE (already have — Minecraft runs on Java) | None (native binary) | Node.js |
| Fat JAR packaging | Yes (Shadow plugin) | N/A (native) | Possible but awkward |
| MCP SDK availability | Kotlin MCP SDK exists | Rust MCP SDK exists | TypeScript MCP SDK exists |
| Coroutines for async | Built-in (kotlinx.coroutines) | Built-in (async/await) | Built-in (async/await) |

Key insight: **Anyone running Minecraft already has a JRE installed.** A Kotlin fat JAR requires zero additional runtime dependencies.

### Shadow JAR Configuration

The MCP server uses the **Gradle Shadow Plugin** (`com.gradleup.shadow`) to produce a fat JAR with all dependencies bundled:

```kotlin
// mcp-server/build.gradle.kts
plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

application {
    mainClass = "dev.minecraftmcp.mcp.MainKt"
}

dependencies {
    // MCP protocol
    implementation("io.modelcontextprotocol:kotlin-sdk:x.x.x")
    // WebSocket client (to connect to mod)
    implementation("io.ktor:ktor-client-cio:x.x.x")
    implementation("io.ktor:ktor-client-websockets:x.x.x")
    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:x.x.x")
}

tasks.shadowJar {
    archiveBaseName.set("minecraft-mcp-server")
    archiveClassifier.set("")  // No "-all" suffix
    archiveVersion.set(project.property("mcp_server_version") as String)
    manifest {
        attributes["Main-Class"] = "dev.minecraftmcp.mcp.MainKt"
    }
}
```

**Output:** `minecraft-mcp-server-0.1.0.jar` — a single executable JAR.

**Usage:** `java -jar minecraft-mcp-server-0.1.0.jar`

### Why Not a Gradle Submodule Sharing Code with Mod?

The MCP server *could* depend on `mod/common` to share protocol types. However:
- The mod is compiled against Minecraft's classpath (Mojang mappings, obfuscated classes)
- Pulling mod/common into the MCP server would drag in Minecraft as a compile dependency
- Better approach: define a small `protocol/` module with just the JSON message types, or simply duplicate the ~10 data classes

**Recommendation:** Start with duplicated protocol types in the MCP server. Extract a shared `protocol` module only if they diverge or grow complex.

---

## 4. Mod Distribution

### Platforms: Modrinth + CurseForge

Both are standard for Minecraft mod distribution. Users download a JAR and drop it in their `mods/` folder.

**Modrinth:**
- Preferred by Fabric community
- Clean API for automated uploads
- Supports dependency declaration (Fabric API)
- `publishMods` Gradle plugin or `modrinth/minotaur` plugin

**CurseForge:**
- Larger user base, especially NeoForge users
- CurseForge API for automated uploads
- `curseforge-gradle-plugin`

### Mod JAR Contents

Each loader-specific JAR is self-contained:

```
minecraft-mcp-fabric-0.1.0.jar
├── META-INF/
│   └── MANIFEST.MF
├── fabric.mod.json              # Fabric metadata
├── assets/
│   └── minecraft-mcp/
│       └── icon.png
└── dev/minecraftmcp/
    ├── (common classes)         # Compiled from mod/common
    └── fabric/                  # Fabric-specific classes
```

The MultiLoader Template handles merging common + loader classes into each output JAR automatically.

### Installation for Users

1. Install Fabric Loader or NeoForge for Minecraft 1.21.4
2. Download `minecraft-mcp-fabric-0.1.0.jar` (or neoforge variant) from Modrinth/CurseForge
3. Place in `.minecraft/mods/`
4. Launch Minecraft — mod starts WebSocket server automatically

---

## 5. MCP Server Distribution

### Distribution Channels

1. **GitHub Releases** — Primary. Attach `minecraft-mcp-server-0.1.0.jar` as release asset.
2. **npm (optional wrapper)** — `npx minecraft-mcp` that downloads and runs the JAR. Convenient for Claude Code users who already have Node.js.
3. **Homebrew tap (optional)** — For macOS users.

### User Installation

**Option A: Direct JAR (recommended)**
```bash
# Download from GitHub releases
curl -L -o minecraft-mcp-server.jar \
  https://github.com/OWNER/minecraft-mcp/releases/latest/download/minecraft-mcp-server.jar
```

**Option B: npm wrapper (convenience)**
```bash
npx @minecraftmcp/server
```

The npm package would be a thin wrapper that:
1. Checks for Java installation
2. Downloads the JAR if not cached
3. Runs `java -jar minecraft-mcp-server.jar` with stdio passthrough

---

## 6. Claude Code MCP Configuration

### `.mcp.json` Format

Claude Code discovers MCP servers via `.mcp.json` in the project root or `~/.claude/mcp.json` globally.

**Project-level config (`.mcp.json`):**
```json
{
  "mcpServers": {
    "minecraft": {
      "command": "java",
      "args": ["-jar", "/path/to/minecraft-mcp-server.jar"],
      "env": {
        "MINECRAFT_MCP_PORT": "25566"
      }
    }
  }
}
```

**Global config (`~/.claude/mcp.json`):**
Same format, applies to all Claude Code sessions.

**With npm wrapper:**
```json
{
  "mcpServers": {
    "minecraft": {
      "command": "npx",
      "args": ["@minecraftmcp/server"]
    }
  }
}
```

### MCP Server Transport

The MCP server communicates with Claude Code via **stdio** (stdin/stdout JSON-RPC). This is the standard MCP transport for CLI tools.

Internally, the MCP server connects to the Minecraft mod via **WebSocket** on localhost.

```
Claude Code <--stdio (JSON-RPC)--> MCP Server <--WebSocket (JSON)--> Minecraft Mod
```

---

## 7. Port Discovery

### Problem

The MCP server needs to know which port the Minecraft mod's WebSocket server is listening on. Default is `25566`, but it may differ (multiple instances, port conflicts).

### Recommended Solution: Port File

When the mod starts its WebSocket server, it writes a **port file** to a known location:

```
~/.minecraft-mcp/
├── port                         # Contains just the port number, e.g., "25566"
├── token                        # Auth token for WebSocket connection
└── instance-{uuid}/             # Multi-instance support (future)
    ├── port
    └── token
```

**Discovery sequence (MCP server startup):**
1. Check `MINECRAFT_MCP_PORT` environment variable
2. Check `--port` CLI argument
3. Read `~/.minecraft-mcp/port` file
4. Fall back to default `25566`

**Port file lifecycle:**
- **Created:** When mod starts WebSocket server (on world load)
- **Deleted:** When mod stops WebSocket server (on world unload/quit)
- Uses atomic write (write to temp file, then rename) to prevent partial reads

### Auth Token

The mod generates a random token on each startup and writes it alongside the port file. The MCP server reads this token and includes it in the WebSocket handshake. This prevents other local processes from connecting.

---

## 8. Development Workflow

### Running Everything in Dev

Developers working on the mod need to run Minecraft + MCP server simultaneously.

**Terminal 1: Minecraft with mod (via Gradle)**
```bash
# Run Fabric dev client
./gradlew :mod:fabric:runClient

# OR run NeoForge dev client
./gradlew :mod:neoforge:runClient
```

Both Fabric Loom and NeoGradle provide `runClient` tasks that launch Minecraft with the mod loaded in a development environment.

**Terminal 2: MCP server**
```bash
./gradlew :mcp-server:run
```

Or after building the shadow JAR:
```bash
java -jar mcp-server/build/libs/minecraft-mcp-server-0.1.0.jar
```

### Gradle Composite Build

The mono-repo uses Gradle's composite build or multi-project setup so that:
- `./gradlew build` builds everything (mod + MCP server)
- `./gradlew :mod:fabric:runClient` runs just the Fabric dev client
- `./gradlew :mcp-server:shadowJar` builds just the MCP server fat JAR
- `./gradlew :mcp-server:run` runs the MCP server directly

### Hot Reload Considerations

- **Mod changes:** Require restarting Minecraft (no hot reload for mods)
- **MCP server changes:** Can restart independently without restarting Minecraft (just reconnects WebSocket)
- **Protocol changes:** Require restarting both

### IDE Setup

IntelliJ IDEA is the standard IDE for Minecraft mod development:
- Import the root `build.gradle.kts`
- Gradle will configure all subprojects
- Run configurations auto-generated by Fabric Loom / NeoGradle
- Add a run configuration for the MCP server (Kotlin application)

---

## 9. CI/CD with GitHub Actions

### Build Workflow (`.github/workflows/build.yml`)

Runs on every push and PR:

```yaml
name: Build
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java: [21]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ matrix.java }}
          distribution: temurin
      - uses: gradle/actions/setup-gradle@v4

      # Build mod (both loaders)
      - run: ./gradlew :mod:fabric:build :mod:neoforge:build

      # Build MCP server shadow JAR
      - run: ./gradlew :mcp-server:shadowJar

      # Run tests
      - run: ./gradlew test

      # Upload artifacts for inspection
      - uses: actions/upload-artifact@v4
        with:
          name: mod-fabric
          path: mod/fabric/build/libs/*.jar
      - uses: actions/upload-artifact@v4
        with:
          name: mod-neoforge
          path: mod/neoforge/build/libs/*.jar
      - uses: actions/upload-artifact@v4
        with:
          name: mcp-server
          path: mcp-server/build/libs/minecraft-mcp-server-*.jar
```

### Release Workflow (`.github/workflows/release.yml`)

Triggered by a version tag (`v*`):

```yaml
name: Release
on:
  push:
    tags: ['v*']

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write  # For GitHub Release
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
      - uses: gradle/actions/setup-gradle@v4

      - run: ./gradlew build :mcp-server:shadowJar

      # Create GitHub Release with all artifacts
      - uses: softprops/action-gh-release@v2
        with:
          files: |
            mod/fabric/build/libs/minecraft-mcp-fabric-*.jar
            mod/neoforge/build/libs/minecraft-mcp-neoforge-*.jar
            mcp-server/build/libs/minecraft-mcp-server-*.jar

      # Publish to Modrinth (both loaders)
      # - uses: Kir-Antipov/mc-publish@v3.3
      #   with:
      #     modrinth-id: XXXXXXXX
      #     modrinth-token: ${{ secrets.MODRINTH_TOKEN }}
      #     files: mod/fabric/build/libs/*.jar
      #     loaders: fabric
      #     game-versions: 1.21.4
```

### Test Strategy

- **Unit tests:** Protocol serialization, command parsing (JVM tests, no Minecraft needed)
- **Integration tests:** MCP server ↔ mock WebSocket server
- **Game tests:** Fabric has `fabric-gametest-api-v1`; NeoForge has built-in game test framework. Can validate mod initialization and basic command execution in a headless server.

---

## 10. Version Compatibility Matrix

| Component | Version | Dependencies |
|-----------|---------|-------------|
| Minecraft | 1.21.4 | Java 21 |
| Fabric Loader | >=0.16.0 | Minecraft 1.21.4 |
| Fabric API | >=0.x.x | Fabric Loader |
| NeoForge | >=21.4.x | Minecraft 1.21.4 |
| Mod | 0.1.0 | Minecraft 1.21.4, one loader |
| MCP Server | 0.1.0 | Java 21 (same JRE as Minecraft) |
| Claude Code | Latest | MCP protocol support |

### Versioning Strategy

- **Mod and MCP server share the same version number** (they're released together from the same repo)
- Use semantic versioning: `MAJOR.MINOR.PATCH`
- Protocol breaking changes bump MINOR (pre-1.0) or MAJOR (post-1.0)
- The MCP server validates mod protocol version on WebSocket connect and warns if mismatched

---

## 11. Summary of Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Repo structure | Mono-repo | Protocol shared, single CI/CD, version sync |
| Mod build | MultiLoader Template | Zero deps, clean separation, matches eval recommendation |
| MCP server language | Kotlin/JVM | Same build system, users already have JRE from Minecraft |
| MCP server packaging | Shadow JAR (fat JAR) | Single `java -jar` execution, zero install steps |
| MCP transport to Claude | stdio (JSON-RPC) | Standard MCP transport for CLI tools |
| Mod-to-server transport | WebSocket (JSON) | Bidirectional, supports event push, Netty already bundled |
| Port discovery | File-based (`~/.minecraft-mcp/port`) | Simple, reliable, no network scanning |
| Auth | Random token in file | Simple, localhost-only, prevents accidental connections |
| Mod distribution | Modrinth + CurseForge | Standard channels, widest reach |
| MCP server distribution | GitHub Releases (JAR) + optional npm wrapper | Direct download + convenience wrapper |
| CI/CD | GitHub Actions | Free for open source, matrix builds, artifact uploads |
| Version strategy | Shared version, semver | Prevents protocol drift |

---

## 12. Open Questions

1. **Shared protocol module:** Should we extract `protocol/` as a shared Gradle module between mod and MCP server? Risk: pulling Minecraft classpath into MCP server build. Start with duplication, extract later.

2. **Multi-instance support:** When multiple Minecraft instances run simultaneously, each writes a different port file. The MCP server needs instance selection. Defer to post-MVP.

3. **MCP server auto-update:** Should the npm wrapper or MCP server itself check for updates? Low priority for MVP.

4. **Kotlin vs Java for MCP server:** Kotlin is recommended for coroutines and MCP SDK availability. However, if the team is more comfortable with Java, the MCP server can be pure Java with CompletableFuture-based async. The Shadow JAR approach works identically for both.

5. **Minecraft version support breadth:** Targeting only 1.21.4 for MVP. Consider 1.21.1 (NeoForge LTS) if user demand exists.
