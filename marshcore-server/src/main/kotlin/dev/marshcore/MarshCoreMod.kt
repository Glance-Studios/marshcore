package dev.marshcore

import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Core server-side systems for the MarshLands SMP. Players install nothing.
 * Houses the one-time first-login Mending reset ([MendingReset]) and a maintenance mode (see the
 * status-ping mixin + the /marshcore maintenance command).
 */
object MarshCoreMod : DedicatedServerModInitializer {

    val LOG = LoggerFactory.getLogger("marshcore")

    lateinit var config: MarshCoreConfig
        private set

    private lateinit var configPath: Path

    override fun onInitializeServer() {
        configPath = FabricLoader.getInstance().configDir.resolve("marshcore.json")
        config = MarshCoreConfig.load(configPath)

        ServerPlayConnectionEvents.JOIN.register(
            ServerPlayConnectionEvents.Join { handler, _, server ->
                val player = handler.player

                // Maintenance: kick non-ops with the configured message.
                if (config.maintenanceEnabled && !server.playerList.isOp(player.nameAndId())) {
                    handler.disconnect(Component.literal(config.maintenanceKickMessage.replace('&', '§')))
                    return@Join
                }

                // One-time first-login Mending reset. addTag returns true only the first time.
                if (config.mendingResetEnabled && player.addTag(MendingReset.FLAG)) {
                    try {
                        if (MendingReset.run(player)) {
                            LOG.info("Applied one-time Mending reset to {}", player.name.string)
                        }
                    } catch (e: Exception) {
                        LOG.error("Mending reset failed for {}", player.name.string, e)
                    }
                }

                // New-season gate: first join of the current season -> spawn them + configured actions.
                // Deferred a tick so the player is fully in the world before the teleport.
                if (config.seasonStartEnabled && player.addTag(SeasonStart.flagFor(config.seasonId))) {
                    server.execute {
                        try {
                            SeasonStart.run(server, player, config)
                            LOG.info("Applied season-{} first-join spawn to {}", config.seasonId, player.name.string)
                        } catch (e: Exception) {
                            LOG.error("Season start failed for {}", player.name.string, e)
                        }
                    }
                }
            },
        )

        // Update-notif gate: registers the C2S pack-version receiver + (optionally) polls latest.json.
        UpdateNotifier.init(config)
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerLifecycleEvents.ServerStopping { UpdateNotifier.shutdown() })

        // Configuration-phase hard gate, before registry sync. Must init after UpdateNotifier, which
        // owns the required version + mandatory flag it reads.
        PackGate.init(config)

        registerCommand()
        LOG.info(
            "MarshCore ready. mendingReset={} maintenance={} updateCheck={} (mandatory={}) seasonStart={} (season {})",
            config.mendingResetEnabled, config.maintenanceEnabled, config.updateCheckEnabled,
            UpdateNotifier.isMandatory(), config.seasonStartEnabled, config.seasonId,
        )
    }

    private fun registerCommand() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            dispatcher.register(
                literal("marshcore")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // op level 2
                    .then(
                        literal("maintenance")
                            .then(literal("on").executes { setMaintenance(it, true); 1 })
                            .then(literal("off").executes { setMaintenance(it, false); 1 })
                            .executes {
                                it.source.sendSuccess(
                                    { Component.literal("[MarshCore] maintenance is ${if (config.maintenanceEnabled) "ON" else "OFF"}") },
                                    false,
                                )
                                1
                            },
                    )
                    .then(
                        literal("season")
                            .then(literal("setspawn").executes { seasonSetSpawn(it); 1 })
                            .then(literal("new").executes { seasonNew(it); 1 })
                            .then(
                                literal("apply").then(
                                    argument("target", EntityArgument.player()).executes { seasonApply(it); 1 },
                                ),
                            )
                            .executes { seasonInfo(it); 1 },
                    )
                    .then(
                        literal("update")
                            .then(literal("check").executes { updateCheck(it); 1 })
                            .then(
                                literal("mandatory")
                                    .then(literal("on").executes { setMandatory(it, true); 1 })
                                    .then(literal("off").executes { setMandatory(it, false); 1 }),
                            )
                            .executes { updateStatus(it); 1 },
                    ),
            )
        })
    }

    private fun updateStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val src = if (config.updateManifestUrl.isBlank()) "static config"
        else "manifest ${config.updateManifestUrl} (every ${config.updateManifestIntervalMinutes}m)"
        val gate = if (UpdateNotifier.isMandatory()) "§cMANDATORY§7 (outdated clients kicked at login)"
        else "§aoptional§7 (outdated clients warned in chat)"
        ctx.source.sendSuccess(
            {
                Component.literal(
                    "§7[MarshCore] update-check=${config.updateCheckEnabled} " +
                        "required=${UpdateNotifier.currentRequiredVersion()} source=$src gate=$gate " +
                        "url=${UpdateNotifier.currentUpdateUrl().ifBlank { "(none)" }}",
                )
            },
            false,
        )
        return 1
    }

    /**
     * Flips [MarshCoreConfig.requiredPackMandatory] live. A manifest "required" field still wins once
     * it has been fetched, so this is the switch for the static/no-manifest setup.
     */
    private fun setMandatory(ctx: CommandContext<CommandSourceStack>, on: Boolean): Int {
        config.requiredPackMandatory = on
        config.save(configPath)
        val effective = UpdateNotifier.isMandatory()
        ctx.source.sendSuccess(
            {
                Component.literal(
                    "[MarshCore] mandatory update ${if (on) "§cON §7(outdated clients kicked at login)" else "§aOFF §7(chat warning only)"}" +
                        if (effective != on) " §e(overridden to $effective by the manifest)" else "",
                )
            },
            true,
        )
        return 1
    }

    private fun updateCheck(ctx: CommandContext<CommandSourceStack>): Int {
        if (UpdateNotifier.requestRefresh()) {
            ctx.source.sendSuccess(
                {
                    Component.literal(
                        "[MarshCore] Manifest refresh queued, new required version applies once fetched " +
                            "(watch console). Currently ${UpdateNotifier.currentRequiredVersion()}.",
                    )
                },
                false,
            )
        } else {
            ctx.source.sendSuccess(
                {
                    Component.literal(
                        "[MarshCore] No manifest URL set, required version is pinned to " +
                            "${UpdateNotifier.currentRequiredVersion()} (config.requiredPackVersion).",
                    )
                },
                false,
            )
        }
        return 1
    }

    private fun seasonSetSpawn(ctx: CommandContext<CommandSourceStack>): Int {
        val p = ctx.source.playerOrException
        val pos = p.position()
        config.spawnDimension = p.level().dimension().identifier().toString()
        config.spawnX = pos.x
        config.spawnY = pos.y
        config.spawnZ = pos.z
        config.spawnYaw = p.yRot
        config.spawnPitch = p.xRot
        config.save(configPath)
        ctx.source.sendSuccess({ Component.literal("[MarshCore] Season spawn set to ${spawnText()}") }, true)
        return 1
    }

    private fun seasonNew(ctx: CommandContext<CommandSourceStack>): Int {
        config.seasonId += 1
        config.save(configPath)
        ctx.source.sendSuccess(
            { Component.literal("[MarshCore] §aSeason ${config.seasonId} started.§7 Every player is sent to spawn on their next join.") },
            true,
        )
        return 1
    }

    private fun seasonApply(ctx: CommandContext<CommandSourceStack>): Int {
        val target = EntityArgument.getPlayer(ctx, "target")
        target.addTag(SeasonStart.flagFor(config.seasonId)) // mark done so a later natural join won't repeat it
        SeasonStart.run(ctx.source.server, target, config)
        ctx.source.sendSuccess({ Component.literal("[MarshCore] Ran season start for ${target.name.string}.") }, true)
        return 1
    }

    private fun seasonInfo(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess(
            { Component.literal("[MarshCore] season=${config.seasonId} enabled=${config.seasonStartEnabled} spawn=${spawnText()}") },
            false,
        )
        return 1
    }

    private fun spawnText(): String =
        "${config.spawnDimension} ${"%.1f".format(config.spawnX)}/${"%.1f".format(config.spawnY)}/${"%.1f".format(config.spawnZ)}"

    private fun setMaintenance(ctx: CommandContext<CommandSourceStack>, on: Boolean): Int {
        config.maintenanceEnabled = on
        config.save(configPath)
        ctx.source.sendSuccess(
            { Component.literal("[MarshCore] maintenance ${if (on) "§cON §7(non-ops kicked, server list shows '${config.maintenanceVersionText}')" else "§aOFF"}") },
            true,
        )
        return 1
    }
}
