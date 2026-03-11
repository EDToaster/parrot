# Autonomous MCP Iteration Guide

A playbook for running a self-improving loop where a root Claude Code agent continuously evaluates and improves the Parrot MCP server by actually using it against a live Minecraft instance.

## Overview

```
┌─────────────────────────────────────────────────────────┐
│ zellij session                                          │
│                                                         │
│  ┌─────────────────┐  ┌─────────────┐  ┌────────────┐  │
│  │  Root Agent      │  │ Coordinator │  │ hive tui   │  │
│  │  (orchestrator)  │  │ (claude)    │  │            │  │
│  │                  │  │             │  │            │  │
│  │  Thinks about    │  │ Runs hive   │  │ Monitoring │  │
│  │  improvements,   │  │ explore or  │  │ dashboard  │  │
│  │  drives panes,   │  │ hive start  │  │            │  │
│  │  builds, tests   │  │ swarm       │  │            │  │
│  └─────────────────┘  └─────────────┘  └────────────┘  │
│                                                         │
│  After build:                                           │
│  ┌─────────────────┐                                    │
│  │  Tester Agent    │                                   │
│  │  (claude + MCP)  │                                   │
│  │                  │                                   │
│  │  Launches MC,    │                                   │
│  │  tries tools,    │                                   │
│  │  writes feedback │                                   │
│  └─────────────────┘                                    │
└─────────────────────────────────────────────────────────┘
```

## Prerequisites

- `zellij` installed and running (the root agent must be inside a zellij session)
- `hive` CLI installed and on PATH
- `claude` CLI installed and on PATH
- Java 21+ and Gradle available for building
- Minecraft instance data in `mod/fabric/runs/` (at least one saved world)

## The Loop

The root agent repeats this cycle:

```
1. THINK    → Review feedback, identify next improvement
2. EXPLORE  → (optional) Use hive explore for divergent ideation
3. PLAN     → Write or refine a spec
4. BUILD    → Use hive start to implement the spec
5. COMPILE  → Run ./gradlew build to verify
6. TEST     → Spawn a tester agent that uses the MCP against live Minecraft
7. ASSESS   → Read the tester's feedback, decide what to do next
```

---

## Step-by-Step

### 1. THINK

The root agent reviews:
- Previous feedback files in `docs/feedback/`
- The current tool list in `mcp-server/src/main/kotlin/dev/parrot/mcp/ToolRegistrar.kt`
- The README for current capabilities

It decides what to improve next. This could be:
- A UX issue found during testing (e.g., "error messages are unhelpful")
- A missing capability (e.g., "no way to read sign text")
- A workflow friction point (e.g., "too many tool calls needed to build a house")

### 2. EXPLORE (optional)

If the improvement needs ideation (multiple possible approaches), use hive explore.

```bash
# Step 1: Set up the hive explore environment
hive explore "improve error messages when player is not in a loaded chunk"

# Step 2: Create two named panes — coordinator and TUI
zellij run -d right -n "coordinator" -- claude --dangerously-skip-permissions
zellij run -d down -n "hive-tui" -- hive tui
```

`zellij run` creates a named pane and starts the command immediately, regardless of which pane is currently focused.

**Driving the coordinator pane:**

The root agent needs to send the coordinator its initial prompt. Since `zellij action` commands (write-chars, dump-screen, etc.) target the **focused** pane, focus it first:

```bash
# Focus the coordinator pane (it was created to the right)
zellij action move-focus right

# Type the start message and press Enter
zellij action write-chars "lets begin"
zellij action write 13
```

**Monitoring the coordinator:**

```bash
# Focus the coordinator pane first
zellij action move-focus right

# Dump its screen to a file
zellij action dump-screen -f /tmp/coordinator-output.txt

# Focus back to root pane
zellij action move-focus left
```

The root agent reads `/tmp/coordinator-output.txt` to see what the coordinator is doing. When the coordinator asks a question (during Phase 1 of explore), the root agent answers by writing to the pane.

**Responding to coordinator questions:**

```bash
zellij action move-focus right
zellij action write-chars "I like option 2, lets proceed with that approach"
zellij action write 13
zellij action move-focus left
```

**Important:** Claude Code sometimes shows "recommended responses" as clickable suggestions. The root agent should ignore these and always type its own responses via `write-chars`.

**When exploration finishes:**

```bash
# Close the coordinator and TUI panes
zellij action move-focus right
zellij action close-pane
# The TUI pane should now be focused (or focus it)
zellij action close-pane
```

### 3. PLAN

After exploration (or directly if the change is straightforward), write the spec:

```bash
# The root agent writes the spec using its own tools (Write tool)
# Save to docs/specs/<feature-name>.md
```

### 4. BUILD (hive start)

```bash
# Step 1: Initialize hive with the spec
hive start docs/specs/<feature-name>.md

# Step 2: Create coordinator and TUI panes
zellij run -d right -n "coordinator" -- claude --dangerously-skip-permissions
zellij run -d down -n "hive-tui" -- hive tui

# Step 3: Tell the coordinator to begin
zellij action move-focus right
zellij action write-chars "Begin!"
zellij action write 13
zellij action move-focus left
```

**Monitoring progress:**

The root agent periodically dumps the coordinator pane to check status:

```bash
zellij action move-focus right
zellij action dump-screen -f /tmp/coordinator-output.txt
zellij action move-focus left
# Read /tmp/coordinator-output.txt
```

It can also check hive status directly:

```bash
hive status
hive agents
```

**When implementation finishes**, close the panes:

```bash
zellij action move-focus right
zellij action close-pane
zellij action close-pane
```

### 5. COMPILE

Before testing, build the project:

```bash
./gradlew build
```

If the build fails, the root agent should either:
- Fix simple issues itself (e.g., import errors)
- Go back to step 4 with a fix spec

### 6. TEST

Spawn a tester agent that actually uses the MCP against live Minecraft.

**Important:** The root agent must NOT use the MCP itself. It spawns a fresh Claude instance that picks up the newly-built MCP server.

```bash
# Create a tester pane
zellij run -d right -n "tester" -- claude --dangerously-skip-permissions
```

Send the tester its mission:

```bash
zellij action move-focus right
zellij action write-chars "You are testing the Parrot MCP server for Minecraft. Your goal is to evaluate the developer experience of using these tools as an AI agent.

Do the following:
1. Check connection_status to see if Minecraft is running
2. If not connected, launch it: ./gradlew :mod:fabric:runClient -Dparrot.world=Creative1 > /tmp/mc.log 2>&1 &
3. Use wait_for_instance to wait for the game to be ready
4. Try a variety of tools - reading world state, performing actions, using consequences
5. Pay attention to: error messages, missing info, awkward multi-step workflows, anything confusing
6. Write your findings to docs/feedback/$(date +%Y-%m-%d-%H%M).md with sections:
   - What worked well
   - What was confusing or awkward
   - Specific improvement suggestions
   - Tools you wished existed

Be thorough. Try edge cases. Try to build something. Try to navigate. Try things that might fail."
zellij action write 13
zellij action move-focus left
```

**Monitoring the tester:**

```bash
zellij action move-focus right
zellij action dump-screen -f /tmp/tester-output.txt
zellij action move-focus left
```

**When the tester finishes** (it will have written its feedback file):

```bash
zellij action move-focus right
zellij action close-pane
```

### 7. ASSESS

The root agent reads the feedback file:

```bash
# Read docs/feedback/<latest>.md
```

Then decides:
- **Minor issues** → Write a spec and go to step 4
- **Needs exploration** → Go to step 2
- **Everything is great** → Stop or pick a new area to improve
- **Converged** → Report to the human that the MCP experience is solid

---

## Zellij Pane Management Reference

**Creating panes** uses `zellij run` — works regardless of which pane is focused:

| Action | Command |
|--------|---------|
| Create named pane to the right | `zellij run -d right -n "name" -- command args` |
| Create named pane below | `zellij run -d down -n "name" -- command args` |

**Interacting with panes** uses `zellij action` — operates on the **focused pane**. Focus a pane first, then interact:

| Action | Command |
|--------|---------|
| Focus right | `zellij action move-focus right` |
| Focus left | `zellij action move-focus left` |
| Focus down | `zellij action move-focus down` |
| Focus up | `zellij action move-focus up` |
| Send text | `zellij action write-chars "text here"` |
| Press Enter | `zellij action write 13` |
| Dump screen to file | `zellij action dump-screen -f /tmp/output.txt` |
| Close focused pane | `zellij action close-pane` |

**Layout convention:** The root agent always occupies the left pane. Child panes are created to the right. The TUI goes below the coordinator.

```
┌──────────┬──────────────┐
│          │ coordinator  │
│  root    ├──────────────┤
│  agent   │ hive tui     │
│          │              │
└──────────┴──────────────┘
```

When only testing (no hive), the layout is simpler:

```
┌──────────┬──────────────┐
│  root    │ tester       │
│  agent   │              │
└──────────┴──────────────┘
```

---

## File Conventions

| Path | Purpose |
|------|---------|
| `docs/specs/<name>.md` | Implementation specs for hive |
| `docs/feedback/<date-time>.md` | Tester agent feedback reports |
| `docs/explore/` | Explorer agent analysis documents |
| `/tmp/coordinator-output.txt` | Transient pane dumps for monitoring |
| `/tmp/tester-output.txt` | Transient pane dumps for monitoring |
| `/tmp/mc.log` | Minecraft stdout/stderr from gradle |

---

## Stopping

The human can stop the loop at any time. The root agent should also stop if:
- Three consecutive test cycles produce no new improvement suggestions
- The tester agent reports "everything works well, no issues found"
- A build fails repeatedly and the root agent can't resolve it

---

## Starting the Root Agent

From inside a zellij session:

```bash
claude --dangerously-skip-permissions -p "You are the root orchestrator for an autonomous MCP improvement loop. Read docs/autonomous-iteration-guide.md for your playbook. Read docs/feedback/ for any prior test results. Begin the loop: think about what to improve, then explore/plan/build/test. Keep iterating until the MCP experience is excellent or I tell you to stop."
```
