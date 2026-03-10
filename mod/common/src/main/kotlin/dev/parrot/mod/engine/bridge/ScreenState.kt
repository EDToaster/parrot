package dev.parrot.mod.engine.bridge
data class ScreenState(val isOpen: Boolean, val screenClass: String, val screenType: String?, val title: String?, val menuType: String?, val slots: List<SlotState>, val widgets: List<WidgetState>)
data class SlotState(val index: Int, val item: String, val count: Int)
data class WidgetState(val type: String, val x: Int, val y: Int, val width: Int, val height: Int, val message: String?, val active: Boolean)
data class ScreenObservation(val type: ScreenObservationType, val screenState: ScreenState?, val tick: Long)
enum class ScreenObservationType { OPENED, CLOSED, CONTENTS_CHANGED }
