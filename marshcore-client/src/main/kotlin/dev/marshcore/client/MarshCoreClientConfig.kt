package dev.marshcore.client

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Client-side settings, at `<gamedir>/config/marshcore-client.json`. Everything here is about *this*
 * machine (where PackPilot lives, how to call it), never about what the server wants: the server's
 * verdict arrives over the wire and is deliberately not allowed to name a program to run.
 */
class MarshCoreClientConfig {

    /**
     * Full path to PackPilot's executable. Blank means auto-detect, which is the normal case: running
     * PackPilot once is enough for it to be found anywhere (see [PackPilotLauncher]). This is only a
     * manual escape hatch for a machine where detection fails.
     */
    @JvmField var packPilotPath: String = ""

    /**
     * Arguments passed to PackPilot when the update button launches it. `{instance}` expands to this
     * game directory and `{pack}` to [packId]. PackPilot ignores unknown arguments today, so this is
     * forward-wiring for when it learns to accept a target.
     */
    @JvmField var packPilotArgs: MutableList<String> =
        mutableListOf("--instance", "{instance}", "--pack", "{pack}")

    /** Pack identifier handed to PackPilot and used in the `packpilot://` deep link. */
    @JvmField var packId: String = "marshlands"

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { GSON.toJson(this, it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun load(path: Path): MarshCoreClientConfig {
            if (Files.exists(path)) {
                runCatching {
                    Files.newBufferedReader(path).use { GSON.fromJson(it, MarshCoreClientConfig::class.java) }
                }.getOrNull()?.let { return it }
            }
            val fresh = MarshCoreClientConfig()
            runCatching { fresh.save(path) }
            return fresh
        }
    }
}
