# Spec: Improve Game Startup & Discovery Experience

## Goal
Make it easy for AI agents to start Minecraft, detect when it's ready, and connect — without relying on a fragile global PID file or manual setup.

## Design Principles
- MCP server stays stateless — no child process management
- Agent launches Minecraft via bash; MCP server provides polling/status tools
- Fix the existing discovery bugs before adding new features
- Keep it simple — ~250 LOC total

---

## Phase 1: Tier 1 — Fix What's Broken

### 1.1 Config.kt — Re-readable discovery + PID validation

**Current problem**: `Config.discover()` runs once at startup. If Minecraft starts after the MCP server, or restarts with a new port/token, the MCP server never picks up the change.

**Changes**:
- Extract `readConnectionFile(): ConnectionInfo?` as a public helper (reads + parses `~/.parrot/connection.json`)
- Add `isPidAlive(pid: Long?): Boolean` — uses `ProcessHandle.of(pid).map { it.isAlive }.orElse(false)`. Returns `true` if pid is null (backwards compat with old files).
- `discover()` now calls `isPidAlive()` and skips stale connection files (dead PID -> fall back to defaults)
- `discover()` remains the main entry point but is now safe to call repeatedly

**Files**: `mcp-server/src/main/kotlin/dev/parrot/mcp/Config.kt`
**LOC**: ~25 new/changed
**Risk**: LOW

### 1.2 MinecraftBridge.kt — Dynamic config + re-discovery on retry

**Current problem**: `MinecraftBridge` takes a fixed `ParrotConfig` at construction and retries the same host:port forever.

**Changes**:
- Change `private val config: ParrotConfig` to `@Volatile var config: ParrotConfig`
- In `connectWithRetry()`, call `Config.discover()` before each connection attempt to pick up new connection.json data
- Add a `gameInfo` field (nullable data class) that caches HelloAck metadata (minecraft_version, mod_loader, mod_version, server_type) after successful handshake
- Clear `gameInfo` in `onDisconnect()`
- Update the "Not connected" error message to: "Not connected to Minecraft. Start the game with the Parrot mod, then use wait_for_instance to connect."

**Files**: `mcp-server/src/main/kotlin/dev/parrot/mcp/MinecraftBridge.kt`
**LOC**: ~80 new/changed
**Risk**: MEDIUM (concurrency — `@Volatile` + careful ordering needed)

### 1.3 ToolRegistrar.kt — `connection_status` tool

**New MCP tool**: `connection_status`
- **Params**: none
- **Returns**:
```json
{
  "connected": true|false,
  "game_info": {
    "minecraft_version": "1.21.10",
    "mod_loader": "fabric",
    "mod_version": "0.1.0",
    "server_type": "integrated"
  },
  "connection_file": {
    "exists": true|false,
    "port": 25566,
    "pid": 12345,
    "pid_alive": true|false
  },
  "hint": "Not connected. Start Minecraft with: ./gradlew :mod:fabric:runClient &"
}
```
- Does NOT go through WebSocket bridge — reads local state only (`bridge.isConnected`, `bridge.gameInfo`, `Config.readConnectionFile()`, `Config.isPidAlive()`)
- Returns a `hint` string when not connected, to guide agents

**Files**: `mcp-server/src/main/kotlin/dev/parrot/mcp/ToolRegistrar.kt`
**LOC**: ~40 new
**Risk**: LOW

### 1.4 Main.kt — Minor wiring change

- No functional change needed beyond what 1.2 handles. `MinecraftBridge(config)` still takes the initial config; the bridge re-discovers on retry.

**Files**: `mcp-server/src/main/kotlin/dev/parrot/mcp/Main.kt`
**LOC**: ~2 changed
**Risk**: LOW

---

## Phase 2: Tier 2 — Enable Agent-Driven Flow

### 2.1 MinecraftBridge.kt — `reconnectTo()` and `connectOnce()`

**New methods on MinecraftBridge**:
- `reconnectTo(newConfig: ParrotConfig)`: Updates config, cancels current retry loop, triggers immediate reconnect attempt
- `connectOnce(config: ParrotConfig): Boolean`: Single connection attempt (connect + hello handshake). Used by `wait_for_instance` to probe readiness without disrupting the main retry loop.

**Files**: `mcp-server/src/main/kotlin/dev/parrot/mcp/MinecraftBridge.kt`
**LOC**: ~40 new
**Risk**: MEDIUM (cancellation of retry coroutine needs care)

### 2.2 ToolRegistrar.kt — `wait_for_instance` tool

**New MCP tool**: `wait_for_instance`
- **Params**:
  - `timeout_seconds`: int, default 120 (Minecraft can take 30-120s to start)
  - `poll_interval_ms`: int, default 2000
- **Returns**:
```json
{
  "status": "connected|timeout|error",
  "connection_info": {
    "port": 25566,
    "pid": 12345,
    "minecraft_version": "1.21.10",
    "mod_loader": "fabric",
    "mod_version": "0.1.0",
    "server_type": "integrated"
  },
  "elapsed_ms": 45000,
  "message": "Connected to Minecraft 1.21.10 (fabric) in 45s"
}
```
- **Algorithm**:
  1. If bridge already connected, return immediately with `"connected"`
  2. Snapshot current connection.json state (to detect changes)
  3. Poll loop until timeout:
     a. Read connection.json
     b. If file exists and (PID changed or port changed from snapshot): validate PID alive
     c. If PID alive: call `bridge.reconnectTo(newConfig)` and wait for handshake
     d. If handshake succeeds: return `"connected"` with game info
     e. Sleep `poll_interval_ms`
  4. On timeout: return `"timeout"` with message

**Files**: `mcp-server/src/main/kotlin/dev/parrot/mcp/ToolRegistrar.kt`
**LOC**: ~80 new
**Risk**: LOW (polling logic is straightforward)

### 2.3 build.gradle.kts — `--quickPlaySingleplayer` support

**Quick win for world auto-loading**: Minecraft 1.20+ supports `--quickPlaySingleplayer <worldFolder>` as a launch argument to auto-load a world.

**Changes**:
- In `mod/fabric/build.gradle.kts`: Read system property `parrot.world` and add as `--quickPlaySingleplayer` program arg if present
- In `mod/neoforge/build.gradle.kts`: Same

**Agent usage**:
```bash
./gradlew :mod:fabric:runClient -Dparrot.world=MyWorld &
```
This launches Minecraft and auto-loads the "MyWorld" save.

**Files**: `mod/fabric/build.gradle.kts`, `mod/neoforge/build.gradle.kts`
**LOC**: ~4 lines each
**Risk**: LOW

---

## Typical Agent Flow

```
Agent: calls connection_status
MCP:   -> {connected: false, hint: "Start Minecraft with: ./gradlew :mod:fabric:runClient &"}

Agent: runs bash: ./gradlew :mod:fabric:runClient -Dparrot.world=Creative1 > /tmp/mc.log 2>&1 &

Agent: calls wait_for_instance(timeout_seconds=120)
MCP:   -> (polls for up to 120s)
MCP:   -> {status: "connected", connection_info: {port: 25566, minecraft_version: "1.21.10", ...}, elapsed_ms: 45000}

Agent: calls get_player
MCP:   -> {name: "Dev", position: {x: 0, y: 64, z: 0}, ...}
```

---

## Test Strategy

### Unit Tests (mcp-server/src/test/)
- `ConfigDiscoveryTest.kt` — extend existing tests:
  - Test `readConnectionFile()` with valid/invalid/missing files
  - Test `isPidAlive()` with current PID (alive), PID 0 (dead), null PID (returns true)
  - Test `discover()` skips stale connection file (dead PID)
- `ConnectionStatusTest.kt` — new:
  - Test tool returns correct shape when connected vs disconnected
  - Test connection_file section with/without file
- `WaitForInstanceTest.kt` — new:
  - Test immediate return when already connected
  - Test timeout behavior
  - Test detection of new connection file appearing

### Integration Tests
- Test full flow: write connection file -> call wait_for_instance -> verify it connects
- Test re-discovery: connect -> disconnect -> write new connection file -> verify reconnect

### Manual Testing
- Start MCP server without Minecraft -> verify connection_status shows hint
- Start Minecraft -> verify wait_for_instance detects it
- Kill Minecraft -> restart -> verify re-discovery works

---

## What's Deferred (Tier 3 / Future)
- Multi-instance support (`~/.parrot/instances/<pid>.json`)
- World creation/loading tools (`list_worlds`, `create_world`, `load_world`)
- Two-phase WebSocket server (lobby mode before world load)
- Embedded MCP server in the mod (no separate process)
- `launch_game` MCP tool (process management inside MCP server)

---

## File Change Summary

| File | Phase | Action | LOC |
|------|-------|--------|-----|
| `mcp-server/src/main/kotlin/dev/parrot/mcp/Config.kt` | 1 | Modify | ~25 |
| `mcp-server/src/main/kotlin/dev/parrot/mcp/MinecraftBridge.kt` | 1+2 | Modify | ~120 |
| `mcp-server/src/main/kotlin/dev/parrot/mcp/ToolRegistrar.kt` | 1+2 | Modify | ~120 |
| `mcp-server/src/main/kotlin/dev/parrot/mcp/Main.kt` | 1 | Modify | ~2 |
| `mod/fabric/build.gradle.kts` | 2 | Modify | ~4 |
| `mod/neoforge/build.gradle.kts` | 2 | Modify | ~4 |
| `mcp-server/src/test/kotlin/dev/parrot/mcp/ConfigDiscoveryTest.kt` | 1 | Modify | ~60 |
| `mcp-server/src/test/kotlin/dev/parrot/mcp/ConnectionStatusTest.kt` | 1 | New | ~50 |
| `mcp-server/src/test/kotlin/dev/parrot/mcp/WaitForInstanceTest.kt` | 2 | New | ~80 |
| **Total** | | | **~465** |
