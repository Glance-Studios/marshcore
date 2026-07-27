package dev.marshcore

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative

/**
 * One-time-per-season action. The first join after [MarshCoreConfig.seasonId] is bumped teleports the
 * player to the configured spawn and shows a welcome, gated per player per season by a tag
 * ([flagFor]). Anything else a player should get once per season belongs in [run].
 */
object SeasonStart {

    /** Per-player, per-season gate tag. `addTag()` returns true only the first join of that season. */
    fun flagFor(seasonId: Int): String = "marshcore_season_$seasonId"

    /** Runs the configured season-start actions for [player]. */
    fun run(server: MinecraftServer, player: ServerPlayer, config: MarshCoreConfig) {
        // Send them to the configured spawn (falling back to the overworld spawn if the dimension
        // id is invalid or that dimension isn't loaded).
        val level = Identifier.tryParse(config.spawnDimension)
            ?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
            ?: server.overworld()

        player.teleportTo(
            level,
            config.spawnX, config.spawnY, config.spawnZ,
            emptySet<Relative>(),
            config.spawnYaw, config.spawnPitch,
            false,
        )

        if (config.seasonWelcomeMessage.isNotBlank()) {
            player.sendSystemMessage(Component.literal(config.seasonWelcomeMessage.replace('&', '§')))
        }

        // --- Add any other one-per-season actions here. ---
    }
}
