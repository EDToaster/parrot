# Adversarial Feasibility Report: OS-Level Client Automation for Minecraft MCP Server

**Explorer:** explorer-automation (adversarial)
**Date:** 2026-03-10
**Objective:** Challenge the need for game-internal hooks by evaluating whether OS-level automation (screenshots + vision + input simulation) could replace a mod-based approach for a Minecraft MCP server.

---

## Executive Summary

**Verdict: External automation is fundamentally insufficient for the mod debugging use case.** While OS-level tools can technically capture screenshots and simulate input, they cannot access the internal game/mod state that is the entire point of this project. The mod-based approach is not just preferable — it is *necessary*.

However, external automation has a narrow complementary role worth acknowledging: it could serve as a *verification layer* to confirm that mod-internal state matches what's rendered on screen.

---

## 1. Screenshot Capture & Vision/OCR

### Tools Evaluated
- **python-mss**: Ultra-fast, cross-platform screenshot capture. Thread-safe, no dependencies. Can capture specific monitors/regions. Suitable for real-time capture at game framerates.
- **PyAutoGUI**: Includes `locateOnScreen()` for template matching. No built-in OCR.
- **Claude Vision API**: Can analyze screenshots with good general understanding.

### Feasibility Assessment

| Capability | Rating | Notes |
|---|---|---|
| Capture screenshots | **Feasible** | python-mss can capture at high FPS |
| Read on-screen text | **Partial** | Claude Vision can read large text; struggles with small/pixelated Minecraft fonts |
| Identify GUI elements | **Partial** | Template matching works for known UI; breaks with resource packs, mods, scaling |
| Understand inventory contents | **Poor** | Item icons are 16x16 pixels; distinguishing similar items is unreliable |
| Read NBT/internal data | **Impossible** | Not rendered on screen |
| Detect mod-specific state | **Impossible** | Internal data structures have no visual representation |

### Latency Profile
- Screenshot capture: **<5ms** (python-mss)
- Claude Vision API call: **1-5+ seconds** per image
- Total per observation cycle: **1-5 seconds** minimum
- Minecraft tick rate: **50ms/tick** (20 ticks/second)
- **Result: 20-100x slower than game speed**

### Cost Profile
- ~1,600 tokens per screenshot at 1092x1092 (~$0.005/image with Opus 4.6)
- A debugging session with continuous monitoring: potentially hundreds of screenshots
- **Estimated cost: $0.50-5.00+ per debugging session** just for vision, before any reasoning

---

## 2. Keyboard/Mouse Simulation

### Tools Evaluated
- **PyAutoGUI**: Cross-platform (Windows/macOS/Linux). 100ms default safety delay. No key state detection.
- **xdotool**: Linux-only. X11 input simulation. Does not work with Wayland.
- **cliclick**: macOS-only. Command-line mouse/keyboard control.

### Feasibility Assessment

| Capability | Rating | Notes |
|---|---|---|
| Click on GUI elements | **Fragile** | Requires knowing exact pixel coordinates; breaks with resolution/scale changes |
| Type chat commands | **Feasible** | Can open chat (T key) and type `/commands` |
| Navigate menus | **Fragile** | Position-dependent; no semantic understanding of menu state |
| Interact during gameplay | **Poor** | Minecraft captures mouse in raw input mode; simulated events may not register |
| Cross-platform consistency | **Poor** | Different tools per OS; no unified reliable approach |

### Critical Issue: Minecraft Raw Input
Minecraft uses LWJGL's raw mouse input when the game window has focus. On many platforms, this bypasses OS-level input event injection, meaning simulated mouse movements may not register in-game. This is a potential hard blocker for mouse-based interaction.

---

## 3. Accessibility APIs

### Assessment: **Non-starter**

Java Accessibility APIs (JAAPI / Java Access Bridge) are designed for standard Swing/AWT UI components. Minecraft renders its entire GUI through custom OpenGL/LWJGL draw calls, completely bypassing the Java accessibility framework. This means:

- **Zero** GUI element information is exposed via accessibility APIs
- **Zero** game state is accessible through OS accessibility interfaces
- No assistive technology can introspect Minecraft's rendering pipeline
- This is a fundamental architectural mismatch, not a configuration issue

---

## 4. Claude Computer Use

### Assessment: **Interesting but wrong tool for this job**

Claude's Computer Use (beta) combines screenshot capture with mouse/keyboard control in an agent loop. It's designed for web navigation and form-filling workflows.

**Why it doesn't fit:**
- Each action cycle (screenshot → reasoning → action) takes 2-10 seconds
- Minecraft operates at 20 ticks/second — the game moves 40-200x faster than the agent
- Computer Use cannot access any game internals
- It's designed for deterministic UIs (web pages, forms), not real-time 3D rendering
- Still in beta with reliability concerns

**Where it *could* theoretically help:**
- Automated visual regression testing of mod GUIs (non-real-time)
- Navigating Minecraft menus to set up test scenarios (if latency is acceptable)
- But even these are better served by a mod that can directly manipulate game state

---

## 5. The Fundamental Problem: Internal State Access

This is the core argument against the external automation approach. The MCP server's purpose is **mod debugging**. Debugging requires access to:

| Data Needed | External Access | Mod-Internal Access |
|---|---|---|
| Block entity data (NBT) | **Impossible** | Direct Java API |
| Item stack details (enchantments, custom data) | **Unreliable** (tiny icons) | Direct Java API |
| Entity state (health, AI goals, pathfinding) | **Very limited** (visual only) | Direct Java API |
| Mod registries and custom objects | **Impossible** | Direct Java API |
| Event bus state (listeners, firing order) | **Impossible** | Direct Java API |
| Exception traces and error logs | **Requires log file parsing** | Direct access + context |
| Network packet contents | **Impossible** | Packet interceptors |
| Tick timing and performance data | **Impossible** | Internal profiler access |
| Custom GUI widget state (mod GUIs) | **Unreliable** (pixel matching) | Direct widget API access |
| World/chunk data | **Impossible** | Direct world API |

**An external automation approach can access approximately 5-10% of the data a mod-based approach can access, and that 5-10% is accessed unreliably and with 20-100x higher latency.**

---

## 6. Honest Comparison

### Where External Automation Wins
1. **No mod installation required** — works with vanilla or any modded instance without code changes
2. **Mod-loader agnostic** — doesn't need separate Fabric/NeoForge implementations
3. **No game version coupling** — screenshot-based approach doesn't break with Minecraft updates (though UI changes still break template matching)
4. **Simpler development** — Python scripts vs. Java mod development

### Where External Automation Loses (Decisively)
1. **Cannot access internal state** — the entire purpose of the tool
2. **Unreliable GUI interaction** — fragile pixel-based detection
3. **20-100x slower** — API round-trip latency vs. in-process method calls
4. **Expensive** — vision API costs per debugging session
5. **Cross-platform fragility** — different input simulation per OS
6. **Raw input bypass** — Minecraft may ignore simulated mouse events
7. **No programmatic command execution** — can only do what a human can do with mouse/keyboard
8. **Resource pack sensitivity** — visual detection breaks with texture changes

---

## 7. Recommendation

**The mod-based approach is the correct architecture for this project.** External automation fundamentally cannot serve the mod debugging use case because it cannot access the data that needs to be debugged.

### Potential Hybrid Role (Low Priority)
External automation could serve as an optional *complement* to the mod-based approach:
- **Visual verification layer**: Confirm that mod-reported state matches what's actually rendered (useful for catching rendering bugs)
- **Accessibility testing**: Verify that mod GUIs render correctly at different resolutions
- **Non-technical user fallback**: For users who can't install the mod, provide a severely limited "screenshot mode"

But these are nice-to-haves, not core functionality. **The mod is the product.**

---

## 8. Technologies Reference

| Tool | Platform | Use Case | Minecraft Compatibility |
|---|---|---|---|
| python-mss | Cross-platform | Screenshot capture | Works (captures any window) |
| PyAutoGUI | Cross-platform | Input simulation + template matching | Fragile (raw input issues) |
| xdotool | Linux (X11 only) | Input simulation | Limited (X11 only, no Wayland) |
| cliclick | macOS only | Input simulation | Limited (macOS only) |
| Claude Vision | API | Image analysis | Works but slow + expensive |
| Claude Computer Use | API (beta) | Full GUI automation | Too slow for real-time |
| Java Access Bridge | Windows | Accessibility | Does not work (LWJGL bypass) |
| JAAPI | Cross-platform | Accessibility | Does not work (LWJGL bypass) |
