package dev.marshcore.client

import dev.marshcore.api.PackVersionPayload
import dev.marshcore.api.PackVersionRequestPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/**
 * Client companion for MarshCore, reporting the baked-in MarshLands pack version on two channels.
 * The configuration phase answers [PackVersionRequestPayload] before registry sync, the last point
 * a readable "update required" screen is possible instead of a registry-mismatch error. The play
 * phase reports the same version on join, which drives the chat warning for non-breaking updates.
 */
object MarshCoreClient : ClientModInitializer {

    private val LOG = LoggerFactory.getLogger("marshcore-client")

    /** Baked-in MarshLands modpack version, reported to the server's update-gate. Bump each release. */
    // TEST: simulating an OUTDATED client (real value is "1.1.0"). Server requires 1.1.0 -> triggers the update warning.
    const val PACK_VERSION = "1.0.0"

    override fun onInitializeClient() {
        // Configuration phase: answer the server's version query before registry sync. Registering the
        // receiver is also what advertises the channel, which is how MarshCore detects the mod.
        PayloadTypeRegistry.clientboundConfiguration()
            .register(PackVersionRequestPayload.TYPE, PackVersionRequestPayload.CODEC)
        PayloadTypeRegistry.serverboundConfiguration()
            .register(PackVersionPayload.TYPE, PackVersionPayload.CODEC)
        ClientConfigurationNetworking.registerGlobalReceiver(PackVersionRequestPayload.TYPE) { payload, context ->
            UpdatePrompt.onQueried(payload.required, payload.url, payload.mandatory)
            context.responseSender().sendPacket(PackVersionPayload(PACK_VERSION))
        }
        PackPilotLauncher.init(
            MarshCoreClientConfig.load(FabricLoader.getInstance().configDir.resolve("marshcore-client.json")),
        )
        UpdatePrompt.install()

        // Report the pack version to the server on join, for the update-check gate. Only sends if the
        // server accepts the channel (i.e. has MarshCore), otherwise it's a no-op.
        PayloadTypeRegistry.serverboundPlay().register(PackVersionPayload.TYPE, PackVersionPayload.CODEC)
        ClientPlayConnectionEvents.JOIN.register(
            ClientPlayConnectionEvents.Join { _, _, _ ->
                if (ClientPlayNetworking.canSend(PackVersionPayload.TYPE)) {
                    ClientPlayNetworking.send(PackVersionPayload(PACK_VERSION))
                }
            },
        )

        LOG.info("marshcore-client ready, reporting pack version {}.", PACK_VERSION)
    }
}
