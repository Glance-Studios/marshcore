package dev.marshcore.client

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * The update button on the kick screen. Vanilla renders the disconnect reason in a plain
 * MultiLineTextWidget with no mouse handling, and only ever adds a button there for the ServerLinks
 * bug-report entry on error disconnects. A button added here also sidesteps the http/https-only rule
 * for links, because the URI goes to the OS directly. [onQueried] arms it, and the next disconnect
 * within [WINDOW_MS] gets the buttons.
 */
object UpdatePrompt {

    private val LOG = LoggerFactory.getLogger("marshcore-client")

    /** How long an "you're out of date" answer stays fresh enough to explain a disconnect. */
    private const val WINDOW_MS = 60_000L

    private const val BUTTON_WIDTH = 220
    private const val BUTTON_HEIGHT = 20

    private val UPDATE_LABEL: Component = Component.literal("Update via PackPilot")
    private val DOWNLOAD_LABEL: Component = Component.literal("Open the download page")

    /** Update link the server handed us (blank if it has none configured). */
    @Volatile private var url: String = ""

    /** When a server was last told this client is out of date. 0 means current, or never asked. */
    @Volatile private var outdatedAt: Long = 0L

    /** Hooks the disconnect screen. Safe to call once at client init. */
    fun install() {
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, width, height ->
                if (screen !is DisconnectedScreen || !isArmed()) return@AfterInit

                val widgets = Screens.getWidgets(screen)
                val x = width / 2 - BUTTON_WIDTH / 2
                var y = height - 28

                // Only offered when PackPilot is actually installed here (or has registered its
                // protocol). A button that silently does nothing is worse than no button.
                if (PackPilotLauncher.isAvailable()) {
                    widgets.add(
                        Button.builder(UPDATE_LABEL) { PackPilotLauncher.launch() }
                            .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                            .build(),
                    )
                    y -= 24
                }

                // The download page covers everyone else, and doubles as the way to get PackPilot.
                if (url.isNotBlank()) {
                    widgets.add(
                        Button.builder(DOWNLOAD_LABEL) { open(url) }
                            .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                            .build(),
                    )
                }
            },
        )
    }

    /**
     * Records what the server asked for. Arms the prompt when [required] does not match the shipped
     * version and disarms it when it does, so a later clean disconnect shows no stale button.
     */
    fun onQueried(required: String, url: String, mandatory: Boolean) {
        if (required == MarshCoreClient.PACK_VERSION) {
            outdatedAt = 0L
            return
        }
        this.url = url
        outdatedAt = System.currentTimeMillis()
        LOG.info(
            "Server wants pack {} and we're on {} (mandatory={}), arming the update prompt.",
            required, MarshCoreClient.PACK_VERSION, mandatory,
        )
    }

    private fun isArmed(): Boolean =
        outdatedAt != 0L && System.currentTimeMillis() - outdatedAt <= WINDOW_MS

    private fun open(target: String) {
        val uri = runCatching { URI(target) }.getOrElse {
            LOG.warn("Refusing to open malformed target {}: {}", target, it.toString())
            return
        }
        LOG.info("Opening {}", uri)
        Util.getPlatform().openUri(uri)
    }
}
