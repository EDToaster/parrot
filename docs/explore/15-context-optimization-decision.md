# Context Optimization: Batch Commands & Smart Tools

**Date:** 2026-03-10
**Status:** DECIDED

## Problem

Each MCP tool call is a full round-trip through Claude's context window. Compound operations (area scanning, multi-container inspection, entity surveys) can consume 10-50x more context than necessary when done as individual tool calls.

Example — "scan 16x16 area for chests, open each, check for diamonds":
- Without optimization: ~50+ tool calls, hundreds of KB of context
- With batch/smart tools: 1-3 tool calls, 2-5KB of context

## Decision

### Layer 1: Smart High-Level Tools (MVP - Critical)
Tools that perform compound operations in a single call:
- `scan_area(from, to, filter)` — returns all matching blocks in a region
- `find_entities(center, radius, type_filter)` — returns all matching entities
- `get_inventories_in_range(center, radius)` — returns all container contents nearby
- `get_screen_full()` — returns complete screen state (type, all slots, all widgets)

### Layer 2: Batch Command Support (MVP - Critical)
A `batch` tool that accepts an array of commands and returns all results:
```json
{
  "tool": "batch",
  "commands": [
    {"method": "get_block", "params": {"x": 1, "y": 2, "z": 3}},
    {"method": "get_block", "params": {"x": 4, "y": 5, "z": 6}},
    {"method": "get_entity", "params": {"id": "zombie-123"}}
  ]
}
```
Returns an array of results in order. Read-only commands can execute in parallel.

### Layer 3: Scripting Escape Hatch (Fast-Follow)
Rhino/JavaScript runtime for custom compound logic:
```json
{
  "tool": "execute_script",
  "language": "javascript",
  "code": "/* find all chests nearby, open each, filter for custom NBT */",
  "timeout_ms": 5000
}
```
Biggest context savings for unpredictable debugging workflows. Requires sandboxing (class allowlist, timeout enforcement, memory limits).

## Rationale

Context window is a scarce resource. Every round-trip costs tokens for: the tool call, the result, Claude's processing, and the next decision. Minimizing round-trips is critical for effective debugging sessions that may run for extended periods.
