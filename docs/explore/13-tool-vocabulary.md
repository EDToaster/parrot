# Minecraft MCP Server — Tool Vocabulary Specification

**Author:** explorer-tools
**Date:** 2026-03-10
**Status:** Draft — MVP tool set for Claude Code integration

---

## Design Principles

1. **AI-first**: Every response is structured JSON that an AI can reason about. No raw text dumps.
2. **Composable**: Small, focused tools that chain together. `get_player` → use position → `get_blocks_around` → find chest → `interact_block` → `get_screen`.
3. **Debugging-oriented**: Tools expose internal state that a human couldn't easily see (NBT data, block properties, entity AI state).
4. **Idempotent reads**: All `get_*` tools are pure reads with no side effects. All `do_*` tools are actions.
5. **Cross-loader**: Every tool is implementable on both Fabric and NeoForge using their respective APIs.

---

## Tool Naming Convention

| Prefix | Meaning | Side Effects |
|--------|---------|-------------|
| `get_*` | Read/query game state | None |
| `do_*` | Perform an action in-game | Yes |
| `subscribe_*` | Start receiving push events | Creates subscription |
| `unsubscribe_*` | Stop receiving push events | Removes subscription |
| `run_*` | Execute commands or scripts | Yes |

---

## 1. World Inspection Tools

### 1.1 `get_block`

Read the block state and block entity data at a specific position.

**Description:** Get the block type, block state properties, and block entity data (if any) at a world position. Useful for inspecting what's at a location — is it a chest? a sign? a redstone component?

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "x": { "type": "integer", "description": "Block X coordinate" },
    "y": { "type": "integer", "description": "Block Y coordinate" },
    "z": { "type": "integer", "description": "Block Z coordinate" },
    "dimension": {
      "type": "string",
      "description": "Dimension ID (default: player's current dimension)",
      "default": "minecraft:overworld"
    }
  },
  "required": ["x", "y", "z"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "block": { "type": "string", "description": "Block registry ID, e.g. 'minecraft:chest'" },
    "properties": {
      "type": "object",
      "description": "Block state properties, e.g. { \"facing\": \"north\", \"type\": \"single\" }",
      "additionalProperties": { "type": "string" }
    },
    "has_block_entity": { "type": "boolean" },
    "block_entity": {
      "type": "object",
      "description": "NBT data of block entity (if present), serialized as JSON",
      "nullable": true
    },
    "light_level": { "type": "integer", "minimum": 0, "maximum": 15 },
    "biome": { "type": "string", "description": "Biome registry ID" }
  }
}
```

**Example:**
```
Tool call: get_block(x=10, y=64, z=-20)
Response: {
  "block": "minecraft:chest",
  "properties": { "facing": "north", "type": "single", "waterlogged": "false" },
  "has_block_entity": true,
  "block_entity": {
    "Items": [
      { "Slot": 0, "id": "minecraft:diamond", "count": 5 },
      { "Slot": 3, "id": "minecraft:iron_ingot", "count": 32 }
    ],
    "Lock": ""
  },
  "light_level": 12,
  "biome": "minecraft:plains"
}
```

---

### 1.2 `get_blocks_area`

Scan a rectangular region of blocks. Essential for understanding the environment around a position.

**Description:** Get all blocks in a rectangular area. Returns a compact representation to avoid overwhelming the AI. Useful for surveying an area, finding specific blocks, or understanding a build.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "from_x": { "type": "integer" },
    "from_y": { "type": "integer" },
    "from_z": { "type": "integer" },
    "to_x": { "type": "integer" },
    "to_y": { "type": "integer" },
    "to_z": { "type": "integer" },
    "dimension": { "type": "string", "default": "minecraft:overworld" },
    "filter": {
      "type": "string",
      "description": "Optional: only return blocks matching this ID (e.g. 'minecraft:chest'). Omit for all blocks."
    },
    "include_air": {
      "type": "boolean",
      "description": "Whether to include air blocks in the response",
      "default": false
    }
  },
  "required": ["from_x", "from_y", "from_z", "to_x", "to_y", "to_z"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "blocks": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "x": { "type": "integer" },
          "y": { "type": "integer" },
          "z": { "type": "integer" },
          "block": { "type": "string" },
          "properties": { "type": "object", "additionalProperties": { "type": "string" } }
        }
      }
    },
    "total_blocks": { "type": "integer", "description": "Total blocks in the area (including air)" },
    "returned_blocks": { "type": "integer", "description": "Number of blocks returned (after filtering)" },
    "truncated": { "type": "boolean", "description": "True if results were truncated due to size limits" }
  }
}
```

**Limits:** Maximum scan volume of 32x32x32 (32,768 blocks). Larger scans are rejected with an error.

**Example:**
```
Tool call: get_blocks_area(from_x=0, from_y=63, from_z=0, to_x=5, to_y=65, to_z=5, filter="minecraft:chest")
Response: {
  "blocks": [
    { "x": 2, "y": 64, "z": 3, "block": "minecraft:chest", "properties": { "facing": "north", "type": "single" } }
  ],
  "total_blocks": 108,
  "returned_blocks": 1,
  "truncated": false
}
```

---

### 1.3 `get_world_info`

Get global world state — time, weather, difficulty, game rules.

**Description:** Get the current state of the world: time of day, weather, difficulty, loaded dimensions, game rules, and server tick count. Useful for understanding the environment context.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "day_time": { "type": "integer", "description": "Time of day in ticks (0-24000)" },
    "game_time": { "type": "integer", "description": "Total ticks since world creation" },
    "is_day": { "type": "boolean" },
    "weather": { "type": "string", "enum": ["clear", "rain", "thunder"] },
    "difficulty": { "type": "string", "enum": ["peaceful", "easy", "normal", "hard"] },
    "game_rules": {
      "type": "object",
      "description": "All game rules as key-value pairs",
      "additionalProperties": { "type": "string" }
    },
    "loaded_dimensions": {
      "type": "array",
      "items": { "type": "string" },
      "description": "List of dimension IDs currently loaded"
    },
    "server_tps": { "type": "number", "description": "Server ticks per second (20 = normal)" },
    "online_players": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Names of online players"
    }
  }
}
```

---

## 2. Player Inspection Tools

### 2.1 `get_player`

Get comprehensive info about a player.

**Description:** Get a player's position, health, hunger, experience, effects, dimension, and equipment. If no player is specified, returns info about the first/only player (typical for singleplayer debugging).

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "player": {
      "type": "string",
      "description": "Player name. Omit for the default player (singleplayer or first connected)."
    }
  }
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "name": { "type": "string" },
    "uuid": { "type": "string" },
    "position": {
      "type": "object",
      "properties": {
        "x": { "type": "number" },
        "y": { "type": "number" },
        "z": { "type": "number" }
      }
    },
    "rotation": {
      "type": "object",
      "properties": {
        "yaw": { "type": "number" },
        "pitch": { "type": "number" }
      }
    },
    "dimension": { "type": "string" },
    "health": { "type": "number" },
    "max_health": { "type": "number" },
    "food_level": { "type": "integer" },
    "saturation": { "type": "number" },
    "experience_level": { "type": "integer" },
    "experience_total": { "type": "integer" },
    "game_mode": { "type": "string", "enum": ["survival", "creative", "adventure", "spectator"] },
    "is_on_ground": { "type": "boolean" },
    "is_sneaking": { "type": "boolean" },
    "is_sprinting": { "type": "boolean" },
    "active_effects": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "effect": { "type": "string", "description": "Effect registry ID" },
          "amplifier": { "type": "integer" },
          "duration_ticks": { "type": "integer" },
          "is_ambient": { "type": "boolean" }
        }
      }
    },
    "equipment": {
      "type": "object",
      "properties": {
        "main_hand": { "$ref": "#/$defs/item_stack" },
        "off_hand": { "$ref": "#/$defs/item_stack" },
        "head": { "$ref": "#/$defs/item_stack" },
        "chest": { "$ref": "#/$defs/item_stack" },
        "legs": { "$ref": "#/$defs/item_stack" },
        "feet": { "$ref": "#/$defs/item_stack" }
      }
    }
  }
}
```

**Shared `item_stack` definition:**
```json
{
  "$defs": {
    "item_stack": {
      "type": "object",
      "properties": {
        "item": { "type": "string", "description": "Item registry ID, e.g. 'minecraft:diamond_sword'" },
        "count": { "type": "integer" },
        "nbt": { "type": "object", "description": "Item NBT/component data" }
      },
      "description": "An item stack. Null/absent if slot is empty."
    }
  }
}
```

---

### 2.2 `get_inventory`

Read the contents of any inventory — player inventory, a container block, or the currently open screen.

**Description:** Get the contents of an inventory. Can target: the player's own inventory, a block entity's inventory at a position, or the currently open container screen. Returns all slots with item data.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "target": {
      "type": "string",
      "enum": ["player", "block", "screen"],
      "description": "'player' = player inventory, 'block' = container at x/y/z, 'screen' = currently open GUI"
    },
    "x": { "type": "integer", "description": "Block X (required if target='block')" },
    "y": { "type": "integer", "description": "Block Y (required if target='block')" },
    "z": { "type": "integer", "description": "Block Z (required if target='block')" },
    "player": { "type": "string", "description": "Player name (for target='player', optional)" }
  },
  "required": ["target"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "inventory_type": { "type": "string", "description": "e.g. 'player', 'chest', 'furnace', 'generic_9x3'" },
    "size": { "type": "integer", "description": "Total number of slots" },
    "slots": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "index": { "type": "integer" },
          "item": { "type": "string", "description": "Item ID or empty string if empty" },
          "count": { "type": "integer" },
          "nbt": { "type": "object", "nullable": true }
        }
      },
      "description": "Only non-empty slots are returned"
    },
    "title": { "type": "string", "description": "Display title of the container (if applicable)" }
  }
}
```

**Example:**
```
Tool call: get_inventory(target="screen")
Response: {
  "inventory_type": "generic_9x3",
  "size": 27,
  "title": "Chest",
  "slots": [
    { "index": 0, "item": "minecraft:diamond", "count": 5, "nbt": null },
    { "index": 3, "item": "minecraft:iron_ingot", "count": 32, "nbt": null }
  ]
}
```

---

## 3. Entity Inspection Tools

### 3.1 `get_entities`

Find and inspect entities near a position or matching criteria.

**Description:** Query entities in a radius around a position. Returns type, position, health, equipment, and custom data. Essential for understanding what mobs/items/entities are around.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "x": { "type": "number", "description": "Center X (default: player position)" },
    "y": { "type": "number", "description": "Center Y (default: player position)" },
    "z": { "type": "number", "description": "Center Z (default: player position)" },
    "radius": { "type": "number", "description": "Search radius in blocks", "default": 16, "maximum": 64 },
    "type_filter": { "type": "string", "description": "Filter by entity type ID, e.g. 'minecraft:zombie'" },
    "limit": { "type": "integer", "description": "Max entities to return", "default": 50 },
    "dimension": { "type": "string" }
  }
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "entities": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "entity_id": { "type": "integer", "description": "Runtime entity ID" },
          "uuid": { "type": "string" },
          "type": { "type": "string", "description": "Entity type registry ID" },
          "custom_name": { "type": "string", "nullable": true },
          "position": {
            "type": "object",
            "properties": { "x": { "type": "number" }, "y": { "type": "number" }, "z": { "type": "number" } }
          },
          "health": { "type": "number", "nullable": true, "description": "Null for non-living entities" },
          "max_health": { "type": "number", "nullable": true },
          "nbt": { "type": "object", "description": "Full NBT data of the entity" }
        }
      }
    },
    "total_found": { "type": "integer" },
    "returned": { "type": "integer" },
    "truncated": { "type": "boolean" }
  }
}
```

---

### 3.2 `get_entity`

Get detailed info about a single entity by ID or UUID.

**Description:** Get the full state of a specific entity including NBT data, AI goals (for mobs), and all attributes. Useful for deep inspection of a specific mob or entity.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "entity_id": { "type": "integer", "description": "Runtime entity ID" },
    "uuid": { "type": "string", "description": "Entity UUID (alternative to entity_id)" }
  }
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "entity_id": { "type": "integer" },
    "uuid": { "type": "string" },
    "type": { "type": "string" },
    "custom_name": { "type": "string", "nullable": true },
    "position": { "type": "object" },
    "velocity": { "type": "object", "properties": { "x": {"type":"number"}, "y": {"type":"number"}, "z": {"type":"number"} } },
    "rotation": { "type": "object", "properties": { "yaw": {"type":"number"}, "pitch": {"type":"number"} } },
    "health": { "type": "number", "nullable": true },
    "max_health": { "type": "number", "nullable": true },
    "equipment": { "type": "object", "description": "Equipment slots (for living entities)" },
    "active_effects": { "type": "array", "description": "Active potion effects" },
    "attributes": {
      "type": "object",
      "description": "Entity attributes (speed, attack damage, etc.)",
      "additionalProperties": { "type": "number" }
    },
    "tags": { "type": "array", "items": { "type": "string" }, "description": "Entity scoreboard tags" },
    "passengers": { "type": "array", "items": { "type": "integer" }, "description": "Entity IDs of passengers" },
    "vehicle": { "type": "integer", "nullable": true, "description": "Entity ID of vehicle" },
    "nbt": { "type": "object", "description": "Full NBT serialization of entity state" }
  }
}
```

---

## 4. Screen/GUI Tools

### 4.1 `get_screen`

Read the currently open GUI screen.

**Description:** Get information about the currently open screen/GUI. Returns the screen type, title, all visible slots/items (for container screens), and available buttons/widgets. This is the primary tool for understanding what the player sees.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "is_open": { "type": "boolean", "description": "Whether any screen is open (false = game HUD only)" },
    "screen_class": { "type": "string", "description": "Java class name of the screen, e.g. 'net.minecraft.client.gui.screens.inventory.ContainerScreen'" },
    "screen_type": {
      "type": "string",
      "description": "Simplified screen type: 'container', 'crafting', 'furnace', 'anvil', 'enchanting', 'villager_trade', 'creative', 'chat', 'settings', 'custom', 'none'"
    },
    "title": { "type": "string", "description": "Screen title text" },
    "menu_type": { "type": "string", "nullable": true, "description": "Menu type registry ID (for container screens)" },
    "slots": {
      "type": "array",
      "nullable": true,
      "items": {
        "type": "object",
        "properties": {
          "index": { "type": "integer" },
          "item": { "type": "string" },
          "count": { "type": "integer" },
          "nbt": { "type": "object", "nullable": true },
          "slot_type": { "type": "string", "description": "'container', 'player_inventory', 'player_hotbar', 'output', 'fuel', 'input'" }
        }
      },
      "description": "Slot contents (for container screens). Only non-empty slots."
    },
    "widgets": {
      "type": "array",
      "nullable": true,
      "items": {
        "type": "object",
        "properties": {
          "index": { "type": "integer" },
          "type": { "type": "string", "description": "'button', 'text_field', 'slider', 'checkbox'" },
          "label": { "type": "string" },
          "enabled": { "type": "boolean" },
          "x": { "type": "integer" },
          "y": { "type": "integer" },
          "width": { "type": "integer" },
          "height": { "type": "integer" }
        }
      },
      "description": "Interactive widgets on the screen (buttons, text fields, etc.)"
    }
  }
}
```

**Example:**
```
Tool call: get_screen()
Response: {
  "is_open": true,
  "screen_class": "net.minecraft.client.gui.screens.inventory.ContainerScreen",
  "screen_type": "container",
  "title": "Large Chest",
  "menu_type": "minecraft:generic_9x6",
  "slots": [
    { "index": 0, "item": "minecraft:diamond_pickaxe", "count": 1, "nbt": { "Damage": 50 }, "slot_type": "container" },
    { "index": 14, "item": "minecraft:ender_pearl", "count": 16, "nbt": null, "slot_type": "container" }
  ],
  "widgets": null
}
```

---

## 5. Action Tools

### 5.1 `do_interact_block`

Simulate a player right-clicking on a block.

**Description:** Simulate the player right-clicking (using) a block at a position. This is how you open chests, press buttons, toggle levers, use crafting tables, etc. After calling this, use `get_screen` to see what opened.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "x": { "type": "integer", "description": "Block X coordinate" },
    "y": { "type": "integer", "description": "Block Y coordinate" },
    "z": { "type": "integer", "description": "Block Z coordinate" },
    "face": {
      "type": "string",
      "enum": ["up", "down", "north", "south", "east", "west"],
      "description": "Which face of the block to interact with",
      "default": "up"
    },
    "hand": {
      "type": "string",
      "enum": ["main_hand", "off_hand"],
      "default": "main_hand"
    }
  },
  "required": ["x", "y", "z"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "result": {
      "type": "string",
      "enum": ["consumed", "pass", "fail"],
      "description": "Interaction result: 'consumed' = block handled it, 'pass' = block didn't handle, 'fail' = couldn't interact"
    },
    "screen_opened": { "type": "boolean", "description": "Whether a GUI screen opened as a result" },
    "block": { "type": "string", "description": "Block that was interacted with" }
  }
}
```

---

### 5.2 `do_click_slot`

Click a slot in the currently open container screen.

**Description:** Simulate clicking a slot in the currently open container GUI. Used for moving items, crafting, trading with villagers, etc. Supports left-click, right-click, and shift-click.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "slot_index": { "type": "integer", "description": "Slot index to click" },
    "button": {
      "type": "string",
      "enum": ["left", "right"],
      "default": "left"
    },
    "shift": {
      "type": "boolean",
      "description": "Whether to shift-click (quick move)",
      "default": false
    }
  },
  "required": ["slot_index"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "cursor_item": {
      "type": "object",
      "nullable": true,
      "description": "Item now held on cursor (if any)"
    },
    "slot_after": {
      "type": "object",
      "description": "State of the clicked slot after the click"
    }
  }
}
```

---

### 5.3 `do_close_screen`

Close the currently open screen.

**Description:** Close whatever GUI screen is currently open, returning to the game view. Equivalent to pressing Escape.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "closed_screen": { "type": "string", "description": "The screen type that was closed" }
  }
}
```

---

### 5.4 `do_click_widget`

Click a button or widget on the current screen.

**Description:** Click a button, checkbox, or other interactive widget on the currently open screen. Use `get_screen` first to discover available widgets and their indices.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "widget_index": { "type": "integer", "description": "Widget index from get_screen response" }
  },
  "required": ["widget_index"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "widget_label": { "type": "string" },
    "screen_changed": { "type": "boolean", "description": "Whether clicking caused the screen to change" }
  }
}
```

---

### 5.5 `do_attack_entity`

Simulate attacking an entity.

**Description:** Simulate the player left-clicking (attacking) an entity. Used for testing damage, drops, or mob behavior when hit.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "entity_id": { "type": "integer", "description": "Runtime entity ID to attack" }
  },
  "required": ["entity_id"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "damage_dealt": { "type": "number" },
    "target_health_after": { "type": "number", "nullable": true },
    "target_alive": { "type": "boolean" }
  }
}
```

---

### 5.6 `do_use_item`

Simulate using the item in the player's hand.

**Description:** Simulate right-clicking with the item in the player's main or off hand. Used for eating food, throwing ender pearls, placing blocks in the air, using bows, etc.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "hand": {
      "type": "string",
      "enum": ["main_hand", "off_hand"],
      "default": "main_hand"
    }
  }
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "result": { "type": "string", "enum": ["consumed", "pass", "fail"] },
    "item_used": { "type": "string", "description": "Item that was in hand" }
  }
}
```

---

### 5.7 `do_interact_entity`

Simulate right-clicking on an entity.

**Description:** Simulate the player right-clicking (interacting with) an entity. Used for trading with villagers, riding horses, naming with name tags, etc.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "entity_id": { "type": "integer", "description": "Runtime entity ID to interact with" },
    "hand": {
      "type": "string",
      "enum": ["main_hand", "off_hand"],
      "default": "main_hand"
    }
  },
  "required": ["entity_id"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "screen_opened": { "type": "boolean" },
    "entity_type": { "type": "string" }
  }
}
```

---

## 6. Command Execution Tools

### 6.1 `run_command`

Execute a Minecraft command.

**Description:** Execute any Minecraft command as if typed in the chat with `/`. The command runs with server-level permissions. Returns the command output/feedback. Use for `/give`, `/tp`, `/summon`, `/effect`, `/data get`, `/scoreboard`, etc.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "The command to execute (without the leading /). e.g. 'give @p diamond 64'"
    }
  },
  "required": ["command"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" },
    "return_value": { "type": "integer", "description": "Command return value (used by command blocks)" },
    "output": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Lines of feedback/output from the command"
    },
    "error": { "type": "string", "nullable": true, "description": "Error message if command failed" }
  }
}
```

**Example:**
```
Tool call: run_command(command="data get entity @p Pos")
Response: {
  "success": true,
  "return_value": 1,
  "output": ["Player has the following entity data: [10.5d, 64.0d, -20.3d]"],
  "error": null
}
```

---

### 6.2 `run_chat`

Send a chat message as the player.

**Description:** Send a chat message as if the player typed it. Useful for interacting with chat-based mod interfaces, typing in chat commands that aren't server commands, or testing chat functionality.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "message": { "type": "string", "description": "The chat message to send" }
  },
  "required": ["message"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" }
  }
}
```

---

## 7. Event Subscription Tools

### 7.1 `subscribe_events`

Subscribe to game events for push notifications.

**Description:** Subscribe to one or more event types. Once subscribed, the MCP server will send notifications as they happen. Subscriptions persist until `unsubscribe_events` is called or the session ends.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "events": {
      "type": "array",
      "items": {
        "type": "string",
        "enum": [
          "chat_message",
          "screen_open",
          "screen_close",
          "block_break",
          "block_place",
          "entity_death",
          "entity_spawn",
          "player_damage",
          "player_death",
          "item_pickup",
          "mod_error",
          "command_output",
          "log_error",
          "tick_lag"
        ]
      },
      "description": "List of event types to subscribe to"
    },
    "filter": {
      "type": "object",
      "description": "Optional filters to narrow events (e.g. { \"entity_type\": \"minecraft:zombie\" })",
      "additionalProperties": true
    }
  },
  "required": ["events"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "subscription_id": { "type": "string", "description": "ID for managing this subscription" },
    "subscribed_events": { "type": "array", "items": { "type": "string" } }
  }
}
```

**Event notification format (pushed via MCP notifications):**
```json
{
  "subscription_id": "sub_abc123",
  "event_type": "screen_open",
  "timestamp": 1710000000,
  "data": {
    "screen_type": "container",
    "title": "Chest",
    "menu_type": "minecraft:generic_9x3"
  }
}
```

---

### 7.2 `unsubscribe_events`

Cancel an event subscription.

**Description:** Stop receiving notifications for a previously created subscription.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "subscription_id": {
      "type": "string",
      "description": "Subscription ID returned by subscribe_events"
    }
  },
  "required": ["subscription_id"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "success": { "type": "boolean" }
  }
}
```

---

## 8. Log and Error Monitoring Tools

### 8.1 `get_log`

Read recent game/mod log output.

**Description:** Get recent log entries from the Minecraft game log. Filterable by level and source. Critical for debugging mod errors — most mod crashes and warnings appear in the log.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "lines": {
      "type": "integer",
      "description": "Number of recent log lines to return",
      "default": 50,
      "maximum": 500
    },
    "level_filter": {
      "type": "string",
      "enum": ["all", "error", "warn", "info", "debug"],
      "default": "all",
      "description": "Minimum log level to include"
    },
    "source_filter": {
      "type": "string",
      "description": "Filter by logger name/source (e.g. 'my_mod' or 'net.minecraft')"
    },
    "search": {
      "type": "string",
      "description": "Text search within log messages"
    }
  }
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "entries": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "timestamp": { "type": "string" },
          "level": { "type": "string", "enum": ["ERROR", "WARN", "INFO", "DEBUG"] },
          "source": { "type": "string", "description": "Logger name" },
          "message": { "type": "string" },
          "stacktrace": { "type": "string", "nullable": true }
        }
      }
    },
    "total_available": { "type": "integer" },
    "returned": { "type": "integer" }
  }
}
```

**Example:**
```
Tool call: get_log(lines=10, level_filter="error", source_filter="mymod")
Response: {
  "entries": [
    {
      "timestamp": "2026-03-10 10:15:32",
      "level": "ERROR",
      "source": "mymod/BlockHandler",
      "message": "NullPointerException in onBlockActivated",
      "stacktrace": "java.lang.NullPointerException\n\tat com.mymod.BlockHandler.onBlockActivated(BlockHandler.java:42)\n\tat ..."
    }
  ],
  "total_available": 1,
  "returned": 1
}
```

---

### 8.2 `get_chat_history`

Read recent chat messages.

**Description:** Get recent chat messages including system messages, player chat, and command feedback. Useful for seeing what happened in chat without subscribing to events.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "lines": {
      "type": "integer",
      "description": "Number of recent messages to return",
      "default": 20,
      "maximum": 100
    }
  }
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "messages": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "timestamp": { "type": "string" },
          "type": { "type": "string", "enum": ["player", "system", "command_feedback", "game_info"] },
          "sender": { "type": "string", "nullable": true },
          "text": { "type": "string" }
        }
      }
    }
  }
}
```

---

## 9. Registry and Mod Introspection Tools

### 9.1 `get_registries`

List entries in Minecraft registries (items, blocks, entities, etc).

**Description:** Query Minecraft registries to discover what blocks, items, entities, biomes, etc. are registered — including those added by mods. Essential for understanding what a mod adds to the game.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {
    "registry": {
      "type": "string",
      "enum": ["block", "item", "entity_type", "block_entity_type", "menu_type", "biome", "enchantment", "potion", "particle_type", "sound_event", "creative_mode_tab"],
      "description": "Which registry to query"
    },
    "namespace_filter": {
      "type": "string",
      "description": "Filter by namespace (e.g. 'mymod' to see only entries from that mod)"
    },
    "search": {
      "type": "string",
      "description": "Search within registry entry names"
    }
  },
  "required": ["registry"]
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "registry": { "type": "string" },
    "entries": {
      "type": "array",
      "items": { "type": "string" },
      "description": "List of registry IDs (e.g. ['mymod:magic_block', 'mymod:enchanted_sword'])"
    },
    "total": { "type": "integer" }
  }
}
```

**Example:**
```
Tool call: get_registries(registry="block", namespace_filter="mymod")
Response: {
  "registry": "block",
  "entries": ["mymod:magic_altar", "mymod:crystal_block", "mymod:enchanting_pedestal"],
  "total": 3
}
```

---

### 9.2 `get_loaded_mods`

List all loaded mods and their metadata.

**Description:** Get a list of all mods currently loaded by the mod loader. Returns mod ID, name, version, and description. Useful for understanding the mod environment.

**inputSchema:**
```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**Response schema:**
```json
{
  "type": "object",
  "properties": {
    "loader": { "type": "string", "enum": ["fabric", "neoforge"] },
    "minecraft_version": { "type": "string" },
    "mods": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "mod_id": { "type": "string" },
          "name": { "type": "string" },
          "version": { "type": "string" },
          "description": { "type": "string" }
        }
      }
    }
  }
}
```

---

## 10. MCP Resources

MCP resources provide persistent, subscribable state that Claude Code can poll or watch. These are exposed as `resource://` URIs.

### 10.1 `resource://minecraft/player/position`

**Description:** The player's current position and dimension. Updates every tick.

**MIME type:** `application/json`

**Content:**
```json
{
  "x": 10.5,
  "y": 64.0,
  "z": -20.3,
  "yaw": 180.0,
  "pitch": 0.0,
  "dimension": "minecraft:overworld"
}
```

---

### 10.2 `resource://minecraft/player/health`

**Description:** The player's current health, food, and status effects.

**MIME type:** `application/json`

**Content:**
```json
{
  "health": 20.0,
  "max_health": 20.0,
  "food_level": 20,
  "saturation": 5.0,
  "active_effects": []
}
```

---

### 10.3 `resource://minecraft/screen`

**Description:** The currently open screen. Empty object if no screen is open.

**MIME type:** `application/json`

**Content:** Same schema as `get_screen` response.

---

### 10.4 `resource://minecraft/world/info`

**Description:** Current world state (time, weather, TPS).

**MIME type:** `application/json`

**Content:**
```json
{
  "day_time": 6000,
  "is_day": true,
  "weather": "clear",
  "server_tps": 20.0,
  "tick_count": 142857
}
```

---

### 10.5 `resource://minecraft/log/recent`

**Description:** The most recent 20 log entries at WARN level or above. Auto-updates.

**MIME type:** `application/json`

**Content:** Array of log entries (same format as `get_log` response entries).

---

### 10.6 `resource://minecraft/mods`

**Description:** List of loaded mods and their versions.

**MIME type:** `application/json`

**Content:** Same schema as `get_loaded_mods` response.

---

## 11. Debugging Workflow Examples

These examples show how tools compose together for common debugging tasks.

### Workflow 1: "My mod's custom chest isn't showing items"

```
1. get_loaded_mods()                           → Confirm the mod is loaded
2. get_registries(registry="block", namespace_filter="mymod")  → Find the custom chest block ID
3. get_player()                                → Get player position
4. get_blocks_area(..., filter="mymod:custom_chest")          → Find nearby custom chests
5. get_block(x, y, z)                          → Inspect the chest's block entity NBT
6. do_interact_block(x, y, z)                  → Open the chest
7. get_screen()                                → See what screen opened and its contents
8. get_log(level_filter="error", source_filter="mymod")       → Check for errors
```

### Workflow 2: "My mod entity's AI isn't working"

```
1. run_command(command="summon mymod:custom_mob ~ ~ ~")       → Spawn the entity
2. get_entities(radius=5, type_filter="mymod:custom_mob")     → Find it
3. get_entity(entity_id=...)                   → Inspect full NBT and AI state
4. subscribe_events(events=["entity_death"])    → Watch for unexpected death
5. get_log(source_filter="mymod")              → Check for AI-related errors
```

### Workflow 3: "Players report a crash when opening my GUI"

```
1. get_log(level_filter="error")               → Find the crash stacktrace
2. get_registries(registry="menu_type", namespace_filter="mymod")  → Verify menu is registered
3. do_interact_block(x, y, z)                  → Try to trigger the GUI
4. get_screen()                                → See if/what opened
5. get_log(lines=20, level_filter="error")     → Get the fresh error
```

### Workflow 4: "I want to test my mod's recipe"

```
1. run_command(command="give @p mymod:ingredient_a 64")       → Get ingredients
2. run_command(command="give @p mymod:ingredient_b 64")
3. do_interact_block(x, y, z)                  → Open crafting table (or custom station)
4. get_screen()                                → Confirm the crafting UI
5. do_click_slot(slot_index=..., ...)          → Place ingredients
6. get_screen()                                → Check if output slot shows result
7. do_click_slot(slot_index=output, shift=true) → Take the result
8. get_inventory(target="player")              → Verify the item is in inventory
```

### Workflow 5: "Monitor my mod for errors while I play"

```
1. subscribe_events(events=["mod_error", "log_error"])        → Watch for errors
2. subscribe_events(events=["screen_open", "entity_death"])   → Watch for game events
   ... (Claude Code receives push notifications as events happen) ...
3. get_log(level_filter="error", source_filter="mymod")       → Investigate when error arrives
```

---

## 12. Tool Count Summary

| Category | Tools | Count |
|----------|-------|-------|
| World Inspection | `get_block`, `get_blocks_area`, `get_world_info` | 3 |
| Player Inspection | `get_player`, `get_inventory` | 2 |
| Entity Inspection | `get_entities`, `get_entity` | 2 |
| Screen/GUI | `get_screen` | 1 |
| Actions | `do_interact_block`, `do_click_slot`, `do_close_screen`, `do_click_widget`, `do_attack_entity`, `do_use_item`, `do_interact_entity` | 7 |
| Commands | `run_command`, `run_chat` | 2 |
| Events | `subscribe_events`, `unsubscribe_events` | 2 |
| Logs | `get_log`, `get_chat_history` | 2 |
| Registry/Mods | `get_registries`, `get_loaded_mods` | 2 |
| **Total Tools** | | **21** |
| **MCP Resources** | | **6** |

---

## 13. Design Decisions and Rationale

### Why `do_*` prefix for actions?
Clearly separates read-only operations (`get_*`) from side-effecting ones (`do_*`). An AI can safely call any `get_*` tool without worrying about changing game state.

### Why separate `get_entities` and `get_entity`?
`get_entities` is for discovery (find what's around), `get_entity` is for deep inspection (full NBT dump of one entity). Keeping them separate avoids overwhelming responses when scanning an area.

### Why `get_inventory` has a `target` parameter instead of being 3 separate tools?
Inventory reading is conceptually one operation regardless of source. The `target` discriminator keeps the tool count manageable while being clear about what's being read.

### Why MCP resources for position/health/screen?
These are "ambient state" that an AI might want to passively monitor. Resources are ideal for state that changes frequently and that the AI might want to subscribe to for updates, without making explicit tool calls.

### Why `get_log` instead of relying only on event subscriptions?
Log reading is pull-based and essential for investigating issues that already happened. Event subscriptions are for going forward. Both are needed.

### Why no `do_move_player` or `do_look_at` tools?
Movement and camera control are continuous, complex actions that don't fit the command/response model well. The AI can use `run_command(command="tp @p ...")` for teleportation. Fine-grained movement control is out of MVP scope and better suited for a scripting escape hatch (post-MVP).

### Volume limits on `get_blocks_area`
32x32x32 = 32K blocks max. Without this limit, an AI could accidentally request millions of blocks. The limit is generous for debugging (covers a large room or building) while preventing accidental lag.
