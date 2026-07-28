package dev.marshcore

import dev.marshcore.api.PackVersionPayload
import dev.marshcore.api.PackVersionRequestPayload
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.Packet
import net.minecraft.server.network.ConfigurationTask
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * The pack-version handshake, run in the configuration phase. An outdated client dies on registry
 * sync before reaching the play phase, so a chat warning never fires; checking here is what allows a
 * readable wall instead of a packet error. A client that never answers, through a missing mod or a
 * timeout, counts as outdated. A mandatory mismatch disconnects, a soft one is let through.
 */
object PackGate {

    private val LOG = MarshCoreMod.LOG
    private const val TICKS_PER_SECOND = 20

    private val TASK_TYPE = ConfigurationTask.Type("marshcore:pack_version")

    /** Tasks waiting on a reply, keyed by the connection that owes us one. */
    private val pending = ConcurrentHashMap<ServerConfigurationPacketListenerImpl, PackVersionTask>()

    private lateinit var config: MarshCoreConfig

    /** Registers the configuration-phase payloads, the reply receiver, and the gate itself. */
    fun init(config: MarshCoreConfig) {
        this.config = config

        PayloadTypeRegistry.clientboundConfiguration()
            .register(PackVersionRequestPayload.TYPE, PackVersionRequestPayload.CODEC)
        PayloadTypeRegistry.serverboundConfiguration()
            .register(PackVersionPayload.TYPE, PackVersionPayload.CODEC)

        // The client's answer. Hopped to the server thread, since completing a task and disconnecting are
        // both server-thread business.
        ServerConfigurationNetworking.registerGlobalReceiver(PackVersionPayload.TYPE) { payload, context ->
            val handler = context.packetListener()
            context.server().execute { pending.remove(handler)?.onReply(payload.version) }
        }

        // BEFORE_CONFIGURE, not CONFIGURE: tasks added here go through Fabric's "early task execution",
        // which holds back vanilla's own configuration (registry sync included) until they finish. By
        // this point Fabric has already traded channel registrations with the client, so canSend is
        // meaningful. Tasks added in CONFIGURE would run after vanilla has started configuring.
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(
            ServerConfigurationConnectionEvents.Configure { handler, _ ->
                if (!config.updateCheckEnabled) return@Configure

                if (ServerConfigurationNetworking.canSend(handler, PackVersionRequestPayload.TYPE)) {
                    val task = PackVersionTask(handler)
                    pending[handler] = task
                    (handler as FabricServerConfigurationPacketListenerImpl).addTask(task)
                } else {
                    // Nothing listening on the channel, so there is no version to check.
                    verdict(handler, null)
                }
            },
        )

        ServerConfigurationConnectionEvents.DISCONNECT.register(
            ServerConfigurationConnectionEvents.Disconnect { handler, _ -> pending.remove(handler) },
        )

        LOG.info(
            "Pack gate armed. mandatory={} timeout={}s",
            UpdateNotifier.isMandatory(), config.updateGateTimeoutSeconds,
        )
    }

    /**
     * Decide what happens to a connection that reported [version] (null = never reported / no mod).
     * Server thread only, since it may disconnect. Returns true if it did, in which case the caller
     * must leave the configuration phase alone: the connection is already gone.
     */
    private fun verdict(handler: ServerConfigurationPacketListenerImpl, version: String?): Boolean {
        val required = UpdateNotifier.currentRequiredVersion()
        if (version == required) return false

        val who = handler.owner.name
        if (!UpdateNotifier.isMandatory()) {
            LOG.info("{} is on pack {} (required {}), soft update, letting them through.", who, version ?: "none", required)
            return false
        }

        LOG.info("{} is on pack {} (required {}), mandatory update, showing the update wall.", who, version ?: "none", required)
        handler.disconnect(wall(version, required))
        return true
    }

    /**
     * The disconnect screen: the configured wall, plus a link line when an update URL is known. The
     * URL is spelled out rather than hidden behind a click, because vanilla renders the kick reason
     * in a widget where click and hover events are inert. `packpilot://` cannot go here at all, since
     * the client only accepts http and https in a [ClickEvent.OpenUrl].
     */
    private fun wall(version: String?, required: String): Component {
        val url = UpdateNotifier.currentUpdateUrl()

        fun format(template: String) = template
            .replace("{version}", version ?: "not installed")
            .replace("{required}", required)
            .replace("{url}", url)
            .replace('&', '§')

        val text = Component.literal(format(config.updateWallMessage))

        val uri = url.takeIf { it.isNotBlank() }?.let { runCatching { URI.create(it) }.getOrNull() }
        if (uri != null) {
            text.append(Component.literal("\n\n"))
            text.append(
                Component.literal(format(config.updateWallLinkText)).withStyle { style ->
                    style.withClickEvent(ClickEvent.OpenUrl(uri))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("§7$url")))
                },
            )
        }
        return text
    }

    /**
     * Holds the configuration phase open until the client reports its pack version, or until
     * [MarshCoreConfig.updateGateTimeoutSeconds] elapses.
     */
    private class PackVersionTask(private val handler: ServerConfigurationPacketListenerImpl) : ConfigurationTask {

        private var ticks = 0
        private var answered = false

        override fun type(): ConfigurationTask.Type = TASK_TYPE

        override fun start(sender: Consumer<Packet<*>>) {
            // The query carries the verdict, so the client can offer its own button after a kick.
            val request = PackVersionRequestPayload(
                UpdateNotifier.currentRequiredVersion(),
                UpdateNotifier.currentUpdateUrl(),
                UpdateNotifier.isMandatory(),
            )
            sender.accept(ServerConfigurationNetworking.createClientboundPacket(request))
        }

        /**
         * Server-thread tick on the *current* task. Always returns false: finishing a task by returning
         * true routes through vanilla, which knows nothing about Fabric's early-task execution and would
         * leave the configuration phase stalled. We release it ourselves via [release] instead.
         */
        override fun tick(): Boolean {
            if (answered) return false
            if (++ticks < config.updateGateTimeoutSeconds.coerceAtLeast(1) * TICKS_PER_SECOND) return false

            answered = true
            pending.remove(handler)
            LOG.warn("{} never answered the pack-version query, treating as outdated.", handler.owner.name)
            release(verdict(handler, null))
            return false
        }

        /** Server thread. Decides first, then releases the phase, so a mandatory kick beats the release. */
        fun onReply(version: String) {
            answered = true
            release(verdict(handler, version))
        }

        /** Hands the configuration phase back to Fabric, unless [kicked] means there's nobody left to hand it to. */
        private fun release(kicked: Boolean) {
            if (kicked) return
            (handler as FabricServerConfigurationPacketListenerImpl).completeTask(TASK_TYPE)
        }
    }
}
