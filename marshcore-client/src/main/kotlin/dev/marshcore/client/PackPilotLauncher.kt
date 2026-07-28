package dev.marshcore.client

import com.google.gson.Gson
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Finds and starts PackPilot: the installed executable first, then `packpilot://`, then nothing.
 * Only the executable branch works today, because PackPilot registers no URL protocol yet.
 *
 * Detection is client-side by design, so the server never names a path to execute. It tries the
 * configured path, the breadcrumb PackPilot writes on every run, the NSIS and MSI install locations,
 * then the uninstall entry, once on a daemon thread so a `reg query` never blocks a frame.
 */
object PackPilotLauncher {

    private val LOG = LoggerFactory.getLogger("marshcore-client")

    /** Bundlers name the installed binary after the product; cargo builds name it after the crate. */
    private val EXE_NAMES = listOf("PackPilot.exe", "packpilot.exe")
    private const val PRODUCT_DIR = "PackPilot"
    private const val BUNDLE_ID = "com.cameron.packpilot"
    private const val UNINSTALL_KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\$BUNDLE_ID"
    private const val PROTOCOL_KEY = "HKCU\\Software\\Classes\\packpilot"
    private const val REGISTRY_TIMEOUT_SECONDS = 5L

    /** File PackPilot drops in its state directory recording where it currently lives. */
    private const val BREADCRUMB_NAME = "install.json"

    /** Names that mean "the thing that installs PackPilot", not PackPilot itself. */
    private val INSTALLER_MARKERS = listOf("setup", "install", "uninstall")

    /** Cap on entries read per folder, so a 10k-file Downloads folder can't turn into a stall. */
    private const val MAX_ENTRIES_SCANNED = 2_000L

    private val GSON = Gson()

    /** Shape of [BREADCRUMB_NAME]. Only `exe` is read; the rest is there for diagnostics. */
    private class Breadcrumb {
        @JvmField var exe: String? = null
        @JvmField var version: String? = null
        @JvmField var updatedAt: Long = 0L
    }

    private lateinit var config: MarshCoreClientConfig

    /** Resolved executable, or null if PackPilot doesn't appear to be installed. */
    @Volatile private var exe: Path? = null

    /** Whether `packpilot://` has a handler registered on this machine. */
    @Volatile private var protocolRegistered = false

    fun init(config: MarshCoreClientConfig) {
        this.config = config
        Thread({ detect() }, "marshcore-packpilot-detect").apply { isDaemon = true }.start()
    }

    /** Whether a launch attempt has any chance of working. Used to avoid showing a dead button. */
    fun isAvailable(): Boolean = exe != null || protocolRegistered

    /**
     * Starts PackPilot, targeting this game directory. Returns false if nothing could be started, in
     * which case the caller should offer the download page instead.
     */
    fun launch(): Boolean {
        val gameDir = FabricLoader.getInstance().gameDir.toAbsolutePath().toString()

        exe?.let { path ->
            val command = buildList {
                add(path.toString())
                config.packPilotArgs.forEach {
                    add(it.replace("{instance}", gameDir).replace("{pack}", config.packId))
                }
            }
            val started = runCatching {
                ProcessBuilder(command)
                    .directory(path.parent?.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
            started.onSuccess {
                LOG.info("Launched PackPilot: {}", command.joinToString(" "))
                return true
            }
            started.onFailure { LOG.warn("Couldn't start {}: {}", path, it.toString()) }
        }

        if (protocolRegistered) {
            val instance = URLEncoder.encode(gameDir, StandardCharsets.UTF_8)
            val link = "packpilot://update?instance=$instance&pack=${config.packId}"
            runCatching { Util.getPlatform().openUri(URI(link)) }
                .onSuccess {
                    LOG.info("Handed {} to the OS.", link)
                    return true
                }
                .onFailure { LOG.warn("Deep link failed: {}", it.toString()) }
        }

        LOG.warn("PackPilot isn't installed here (or couldn't be started).")
        return false
    }

    private fun detect() {
        val configured = config.packPilotPath.trim()
        if (configured.isNotEmpty()) {
            val path = Path.of(configured)
            if (Files.isRegularFile(path)) {
                exe = path
                LOG.info("Using configured PackPilot at {}.", path)
            } else {
                LOG.warn("packPilotPath is set to {} but there's no file there.", path)
            }
        }

        // The breadcrumb PackPilot leaves behind on every run. This is what makes a portable, never
        // installed exe work with no setup, and it self-heals when the player moves the exe: the next
        // run rewrites the path.
        if (exe == null) {
            exe = exeFromBreadcrumb()
        }

        if (exe == null && Util.getPlatform() == Util.OS.WINDOWS) {
            exe = candidatePaths().firstOrNull { Files.isRegularFile(it) }
                ?: installLocationFromRegistry()?.let { dir ->
                    EXE_NAMES.map(dir::resolve).firstOrNull { Files.isRegularFile(it) }
                }
                ?: searchLikelyFolders()
            protocolRegistered = registryKeyExists(PROTOCOL_KEY)
        }

        LOG.info(
            "PackPilot detection done. exe={} protocol={}",
            exe?.toString() ?: "not found", protocolRegistered,
        )
    }

    /**
     * Reads `<app data>/PackPilot/install.json`, which PackPilot writes on every launch as
     * `{ "exe", "version", "updatedAt" }`. Running PackPilot once is the whole setup, wherever the
     * player keeps it.
     */
    private fun exeFromBreadcrumb(): Path? {
        val file = stateDir()?.resolve(BREADCRUMB_NAME) ?: return null
        if (!Files.isRegularFile(file)) return null

        val recorded = runCatching {
            Files.newBufferedReader(file).use { GSON.fromJson(it, Breadcrumb::class.java) }?.exe
        }.getOrElse {
            LOG.warn("Couldn't read {}: {}", file, it.toString())
            null
        }
        if (recorded.isNullOrBlank()) return null

        val path = runCatching { Path.of(recorded) }.getOrNull() ?: return null
        if (!Files.isRegularFile(path)) {
            LOG.info("{} points at {}, which is gone. Run PackPilot once to refresh it.", file, path)
            return null
        }
        LOG.info("Found PackPilot via its breadcrumb at {}.", path)
        return path
    }

    /** Where PackPilot keeps its own state (it already writes `transactions/` here). */
    private fun stateDir(): Path? = when (Util.getPlatform()) {
        Util.OS.WINDOWS -> System.getenv("APPDATA")?.let { Path.of(it, PRODUCT_DIR) }
        Util.OS.OSX -> System.getProperty("user.home")
            ?.let { Path.of(it, "Library", "Application Support", PRODUCT_DIR) }
        else -> (System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
            ?: System.getProperty("user.home")?.let { Path.of(it, ".config") })?.resolve(PRODUCT_DIR)
    }

    /** Tauri's NSIS bundle installs per-user, the MSI per-machine. Both keep the product-name folder. */
    private fun candidatePaths(): List<Path> {
        val roots = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, PRODUCT_DIR) },
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Programs", PRODUCT_DIR) },
            System.getenv("ProgramFiles")?.let { Path.of(it, PRODUCT_DIR) },
            System.getenv("ProgramFiles(x86)")?.let { Path.of(it, PRODUCT_DIR) },
        )
        return roots.flatMap { root -> EXE_NAMES.map(root::resolve) }
    }

    /**
     * Last resort for a portable exe that was moved before it was ever run: look where downloads
     * actually land. Each folder is listed once, plus one level into any PackPilot-ish subfolder, so
     * this stays a handful of directory reads rather than a disk crawl.
     */
    private fun searchLikelyFolders(): Path? {
        val home = System.getProperty("user.home")?.let { Path.of(it) }
        val roots = listOfNotNull(
            home?.resolve("Downloads"),
            home?.resolve("Desktop"),
            home,
            FabricLoader.getInstance().gameDir.toAbsolutePath(),
            FabricLoader.getInstance().gameDir.toAbsolutePath().parent,
        )

        for (root in roots) {
            findExeIn(root)?.let { return it }
            listDir(root)
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith(PRODUCT_DIR, ignoreCase = true) }
                .forEach { sub -> findExeIn(sub)?.let { return it } }
        }
        return null
    }

    private fun findExeIn(dir: Path): Path? = listDir(dir).firstOrNull { candidate ->
        val name = candidate.fileName.toString()
        name.startsWith(PRODUCT_DIR, ignoreCase = true) &&
            name.endsWith(".exe", ignoreCase = true) &&
            // The bundled installers sit right next to the app in a downloads folder.
            INSTALLER_MARKERS.none { name.contains(it, ignoreCase = true) } &&
            Files.isRegularFile(candidate)
    }

    private fun listDir(dir: Path): List<Path> = runCatching {
        Files.list(dir).use { it.limit(MAX_ENTRIES_SCANNED).toList() }
    }.getOrElse { emptyList() }

    private fun installLocationFromRegistry(): Path? {
        val output = registryQuery(listOf("query", UNINSTALL_KEY, "/v", "InstallLocation")) ?: return null
        val value = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("InstallLocation") }
            ?.substringAfter("REG_SZ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return runCatching { Path.of(value) }.getOrNull()?.takeIf { Files.isDirectory(it) }
    }

    private fun registryKeyExists(key: String): Boolean = registryQuery(listOf("query", key)) != null

    /** Runs `reg` and returns its output, or null if the key is missing or the call misbehaves. */
    private fun registryQuery(args: List<String>): String? = runCatching {
        val process = ProcessBuilder(listOf("reg") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(REGISTRY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        if (process.exitValue() == 0) output else null
    }.getOrElse {
        LOG.debug("Registry lookup failed: {}", it.toString())
        null
    }
}
