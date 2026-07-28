package dev.marshcore

import com.google.gson.Gson
import dev.marshcore.api.PackVersionPayload
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The soft half of the update check, and the source of truth for the required pack version. Clients
 * report their baked-in version on join and get a chat warning on mismatch; [PackGate] is the hard
 * half, one phase earlier. The version is pinned, or polled on a daemon thread from a remote
 * `latest.json` carrying `version`, `message` and `url`.
 *
 * WIP: exact-string version compare, no download or apply, and no signature on the fetch.
 */
object UpdateNotifier {

    private val LOG = MarshCoreMod.LOG
    private val GSON = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Remote manifest shape. Only [version] is required; [message], [url] and [required] override the
     * matching config values when present.
     */
    private class Manifest {
        @JvmField var version: String? = null
        @JvmField var message: String? = null
        @JvmField var url: String? = null
        @JvmField var required: Boolean? = null
    }

    /** Required pack version currently in effect. Seeded from config, replaced by manifest fetches. */
    @Volatile private var requiredVersion: String = ""

    /** Message override from the manifest (null = fall back to [MarshCoreConfig.updateMessage]). */
    @Volatile private var messageOverride: String? = null

    /** Hard-gate override from the manifest (null = fall back to [MarshCoreConfig.requiredPackMandatory]). */
    @Volatile private var mandatoryOverride: Boolean? = null

    /** Update-link override from the manifest (null = fall back to [MarshCoreConfig.updateUrl]). */
    @Volatile private var urlOverride: String? = null

    private lateinit var config: MarshCoreConfig
    private var scheduler: ScheduledExecutorService? = null

    /** Registers the C2S receiver and, if a manifest URL is configured, starts background polling. */
    fun init(config: MarshCoreConfig) {
        this.config = config
        requiredVersion = config.requiredPackVersion

        // C2S receiver: clients report their pack version on join; warn if out of date.
        PayloadTypeRegistry.serverboundPlay().register(PackVersionPayload.TYPE, PackVersionPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(PackVersionPayload.TYPE) { payload, context ->
            val clientVer = payload.version
            val player = context.player()
            context.server().execute {
                val required = requiredVersion
                if (config.updateCheckEnabled && clientVer != required) {
                    val template = messageOverride ?: config.updateMessage
                    val msg = template
                        .replace("{version}", clientVer)
                        .replace("{required}", required)
                        .replace('&', '§')
                    player.sendSystemMessage(Component.literal(msg))
                    LOG.info("{} is on pack {} (required {}), warned.", player.name.string, clientVer, required)
                }
            }
        }

        // Background manifest polling, if a URL is configured.
        if (config.updateManifestUrl.isNotBlank()) {
            val interval = config.updateManifestIntervalMinutes.coerceAtLeast(1).toLong()
            scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "marshcore-update-poll").apply { isDaemon = true }
            }.also { exec ->
                // Fetch once now, then every [interval] minutes.
                exec.scheduleAtFixedRate({ safeFetch() }, 0, interval, TimeUnit.MINUTES)
            }
            LOG.info("Update manifest polling every {}m from {}", interval, config.updateManifestUrl)
        } else {
            LOG.info("Update manifest URL not set, required version pinned to {}.", requiredVersion)
        }
    }

    /** The required version currently in effect (for /marshcore update status). */
    fun currentRequiredVersion(): String = requiredVersion

    /**
     * Whether an outdated client is hard-gated in the configuration phase (see [PackGate]) rather than
     * just warned in chat. Manifest "required" wins; config is the fallback.
     */
    fun isMandatory(): Boolean = mandatoryOverride ?: config.requiredPackMandatory

    /** The update link currently in effect. Manifest "url" wins; config is the fallback. */
    fun currentUpdateUrl(): String = urlOverride ?: config.updateUrl

    /**
     * Queue an immediate off-thread manifest fetch (e.g. from `/marshcore update check`). Returns false
     * if no manifest URL is configured (nothing to fetch). The new version applies once the fetch lands.
     */
    fun requestRefresh(): Boolean {
        val exec = scheduler ?: return false
        exec.execute { safeFetch() }
        return true
    }

    fun shutdown() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    private fun safeFetch() {
        try {
            fetch()
        } catch (e: Exception) {
            // Keep the last-known-good requiredVersion; just log and try again next tick.
            LOG.warn("Update manifest fetch failed ({}): {}", config.updateManifestUrl, e.toString())
        }
    }

    /** One manifest fetch. Runs on the poll thread, so it must never touch the server thread. */
    private fun fetch() {
        val req = HttpRequest.newBuilder(URI.create(config.updateManifestUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            LOG.warn("Update manifest HTTP {} from {}", resp.statusCode(), config.updateManifestUrl)
            return
        }
        val manifest = GSON.fromJson(resp.body(), Manifest::class.java)
        val v = manifest?.version?.trim()
        if (v.isNullOrEmpty()) {
            LOG.warn("Update manifest missing 'version' field, ignoring.")
            return
        }
        if (v != requiredVersion) LOG.info("Required pack version {} -> {} (from manifest).", requiredVersion, v)
        requiredVersion = v
        messageOverride = manifest.message?.takeIf { it.isNotBlank() }
        urlOverride = manifest.url?.takeIf { it.isNotBlank() }
        manifest.required?.let {
            if (it != mandatoryOverride) LOG.info("Update is now {} (from manifest).", if (it) "MANDATORY" else "optional")
            mandatoryOverride = it
        }
    }
}
