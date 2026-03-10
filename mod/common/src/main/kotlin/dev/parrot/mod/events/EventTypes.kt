package dev.parrot.mod.events

enum class ParrotEventType(val id: String) {
    SCREEN_OPENED("screen_opened"), SCREEN_CLOSED("screen_closed"),
    BLOCK_CHANGED("block_changed"), ENTITY_SPAWNED("entity_spawned"),
    ENTITY_REMOVED("entity_removed"), CHAT_MESSAGE("chat_message"),
    INVENTORY_CHANGED("inventory_changed"), PLAYER_HEALTH_CHANGED("player_health_changed"),
    PLAYER_MOVED("player_moved"), DIMENSION_CHANGED("dimension_changed"),
    DEATH("death"), RESPAWN("respawn"),
    ADVANCEMENT("advancement"), BLOCK_BREAK_PROGRESS("block_break_progress");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ParrotEventType? = byId[id]
    }
}
