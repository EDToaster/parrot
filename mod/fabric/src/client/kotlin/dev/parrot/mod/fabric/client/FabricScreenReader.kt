package dev.parrot.mod.fabric.client

import dev.parrot.mod.engine.bridge.ScreenReader
import dev.parrot.mod.engine.bridge.ScreenState
import dev.parrot.mod.engine.bridge.SlotState
import dev.parrot.mod.engine.bridge.WidgetState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries

class FabricScreenReader : ScreenReader {
    override fun getCurrentScreen(): ScreenState? {
        val screen = Minecraft.getInstance().screen ?: return null
        val title = screen.title.string
        val screenClass = screen.javaClass.simpleName

        val slots = if (screen is AbstractContainerScreen<*>) {
            screen.menu.slots.map { slot ->
                SlotState(
                    index = slot.index,
                    item = BuiltInRegistries.ITEM.getKey(slot.item.item).toString(),
                    count = slot.item.count
                )
            }
        } else emptyList()

        val menuType = if (screen is AbstractContainerScreen<*>) {
            try {
                val type = screen.menu.type
                BuiltInRegistries.MENU.getKey(type)?.toString()
            } catch (_: Exception) { null }
        } else null

        // Spec calls for screen.renderables, but that field is private in Fabric's
        // MC 1.21.10 mappings. children() is the accessible alternative and returns
        // the same interactive widgets (buttons, sliders, etc.) as renderables would.
        val widgets = screen.children().filterIsInstance<AbstractWidget>().map { widget ->
            WidgetState(
                type = widget.javaClass.simpleName,
                x = widget.x,
                y = widget.y,
                width = widget.width,
                height = widget.height,
                message = widget.message.string,
                active = widget.active
            )
        }

        return ScreenState(
            isOpen = true,
            screenClass = screenClass,
            screenType = screenClass,
            title = title,
            menuType = menuType,
            slots = slots,
            widgets = widgets
        )
    }
}
