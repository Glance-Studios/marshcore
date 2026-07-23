package dev.marshcore

import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments
import java.util.IdentityHashMap

/**
 * One-time first-login Mending reset. Strips Mending from the inventory, ender chest and shulker
 * contents recursively, keeping the items and leaving enchanted books alone. Two or more stripped
 * items are compensated with a Mending book, exactly one with a golden equivalent of that item.
 */
object MendingReset {

    private const val MENDING = "minecraft:mending"
    const val FLAG = "marshcore_mending_reset"

    private val GOLD_SUFFIXES = listOf(
        "_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
        "_helmet", "_chestplate", "_leggings", "_boots",
    )
    private val EQUIPMENT = arrayOf(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND,
    )

    private class Tally {
        var count = 0
        var lastItemId: String? = null
        var mendingHolder: Holder<Enchantment>? = null
    }

    /** Returns true if anything was changed (Mending found). */
    fun run(player: ServerPlayer): Boolean {
        val t = Tally()
        // Dedupe by object identity so a mainhand item (which appears both as an inventory slot and
        // as the MAINHAND equipment slot) isn't counted twice.
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<ItemStack, Boolean>())

        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (processStack(s, t, seen)) inv.setItem(i, s)
        }
        for (slot in EQUIPMENT) {
            val s = player.getItemBySlot(slot)
            if (processStack(s, t, seen)) player.setItemSlot(slot, s)
        }
        val ender = player.enderChestInventory
        for (i in 0 until ender.containerSize) {
            val s = ender.getItem(i)
            if (processStack(s, t, seen)) ender.setItem(i, s)
        }

        if (t.count == 0) return false

        when {
            t.count >= 2 -> {
                giveMendingBook(player, t.mendingHolder!!)
                player.sendSystemMessage(Component.literal("§6[Mending Rework] §fMending was removed from §e${t.count}§f of your items. You keep the items; here's §b1 Mending book§f."))
            }
            else -> {
                val id = t.lastItemId!!
                when {
                    id.startsWith("minecraft:golden_") ->
                        player.sendSystemMessage(Component.literal("§6[Mending Rework] §fMending was removed from your golden gear. (Already golden, no replacement.)"))
                    goldenEquivalent(id) != null -> {
                        giveItem(player, goldenEquivalent(id)!!)
                        player.sendSystemMessage(Component.literal("§6[Mending Rework] §fMending was removed from 1 item. Here's a §e${prettify(goldenEquivalent(id)!!)}§f."))
                    }
                    else -> {
                        giveMendingBook(player, t.mendingHolder!!)
                        player.sendSystemMessage(Component.literal("§6[Mending Rework] §fMending was removed from 1 item (no golden equivalent). Here's a §bMending book§f."))
                    }
                }
            }
        }
        player.inventoryMenu.broadcastChanges()
        return true
    }

    /** Strips Mending from the stack and recurses into container (shulker) contents. Returns true if changed. */
    private fun processStack(stack: ItemStack, t: Tally, seen: MutableSet<ItemStack>): Boolean {
        if (stack.isEmpty || !seen.add(stack)) return false
        var changed = false

        val holder = stripMending(stack)
        if (holder != null) {
            t.count++
            t.lastItemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            t.mendingHolder = holder
            changed = true
        }

        val contents = stack.get(DataComponents.CONTAINER)
        if (contents != null) {
            val items = contents.allItemsCopyStream().toList() // copies; mutating them is safe
            var innerChanged = false
            for (inner in items) if (processStack(inner, t, seen)) innerChanged = true
            if (innerChanged) {
                stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items))
                changed = true
            }
        }
        return changed
    }

    private fun stripMending(stack: ItemStack): Holder<Enchantment>? {
        val holder = stack.enchantments.keySet().firstOrNull { it.registeredName == MENDING } ?: return null
        EnchantmentHelper.updateEnchantments(stack) { m -> m.removeIf { it === holder } }
        return holder
    }

    private fun goldenEquivalent(id: String): String? {
        if (id.startsWith("minecraft:golden_")) return null
        for (s in GOLD_SUFFIXES) if (id.endsWith(s)) return "minecraft:golden$s"
        return null
    }

    private fun giveMendingBook(player: ServerPlayer, holder: Holder<Enchantment>) {
        val book = ItemStack(Items.ENCHANTED_BOOK)
        val e = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
        e.set(holder, 1)
        book.set(DataComponents.STORED_ENCHANTMENTS, e.toImmutable())
        giveStack(player, book)
    }

    private fun giveItem(player: ServerPlayer, id: String) {
        val loc = Identifier.tryParse(id) ?: return
        val item = BuiltInRegistries.ITEM.getOptional(loc).orElse(null) ?: return
        giveStack(player, ItemStack(item))
    }

    private fun giveStack(player: ServerPlayer, stack: ItemStack) {
        if (!player.inventory.add(stack)) player.drop(stack, false)
    }

    private fun prettify(id: String): String =
        id.substringAfter(':').replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}
