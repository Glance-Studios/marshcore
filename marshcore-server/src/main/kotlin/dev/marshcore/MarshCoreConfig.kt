package dev.marshcore

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/** Config for MarshCore's server-side systems. */
class MarshCoreConfig {

    /** Master switch for the one-time first-login Mending reset. */
    @JvmField var mendingResetEnabled: Boolean = true

    /**
     * Maintenance mode. When on, the server-list "version" slot shows [maintenanceVersionText] in red
     * (and the client can't connect), and non-op players are kicked with [maintenanceKickMessage].
     * Toggle in-game with /marshcore maintenance on|off.
     */
    @JvmField var maintenanceEnabled: Boolean = false

    /** Text shown in the server-list version slot while in maintenance (appears red). */
    @JvmField var maintenanceVersionText: String = "Maintenance"

    /** Kick message shown to non-op players who try to join during maintenance (&-color codes, \n for lines). */
    @JvmField var maintenanceKickMessage: String = "&c&lMaintenance\n&7We'll be back shortly."

    /** Update-check gate: warn players whose reported pack version doesn't match [requiredPackVersion]. */
    @JvmField var updateCheckEnabled: Boolean = true

    /** The pack version clients should be on. Bump this each release (later: synced from latest.json). */
    @JvmField var requiredPackVersion: String = "1.1.0"

    /**
     * Hard-gate switch. When true, an outdated client is disconnected in the configuration phase with
     * [updateWallMessage] instead of being let in with a chat warning (see PackGate). Use it for
     * breaking updates. A manifest with a "required" field overrides this value.
     */
    @JvmField var requiredPackMandatory: Boolean = false

    /**
     * The text wall shown on the disconnect screen when a mandatory update is missing, with &-colour
     * codes and \n for lines. {version} is the reported version, "not installed" if none arrived,
     * {required} is the required one, and {url} is [updateUrl].
     */
    @JvmField var updateWallMessage: String =
        "&c&lUpdate Required\n\n&7You're on MarshLands pack &f{version}&7, and this server needs " +
            "&f{required}&7.\n&7Update the pack in PackPilot, then reconnect."

    /**
     * Last line of the wall, shown only when an update URL is known. Print {url} somewhere in it: the
     * disconnect screen renders the reason as plain text, so a link the player can't read is a dead end.
     * Players who have marshcore-client installed also get a real update button on that screen.
     */
    @JvmField var updateWallLinkText: String = "&7Get it here: &b&n{url}"

    /**
     * Where the update lives, used for the clickable line on the wall. A manifest "url" field overrides
     * this. Must be http/https: the client refuses to open anything else.
     */
    @JvmField var updateUrl: String = ""

    /**
     * How long (seconds) to hold the configuration phase waiting for the client's version report before
     * treating it as outdated.
     */
    @JvmField var updateGateTimeoutSeconds: Int = 10

    /** Message shown to out-of-date players on join (&-color codes; {version} = their version). */
    @JvmField var updateMessage: String =
        "&e&lMarshLands pack update available! &r&7You're on &f{version}&7, latest is &f{required}&7. Update via PackPilot."

    /**
     * Optional remote manifest (latest.json) URL. When set, MarshCore polls it and uses its "version"
     * as the required pack version, overriding [requiredPackVersion]. Blank = disabled (static value).
     * Expected JSON: { "version": "1.2.0", "message": "<optional override>", "url": "<optional link>" }
     */
    @JvmField var updateManifestUrl: String = ""

    /** How often (minutes, min 1) to re-poll [updateManifestUrl]. Only used when the URL is set. */
    @JvmField var updateManifestIntervalMinutes: Int = 15

    /**
     * Master switch for the once-per-season "new season" action: the first time a player joins after
     * [seasonId] is bumped, they're sent to the spawn below (see [SeasonStart]).
     */
    @JvmField var seasonStartEnabled: Boolean = true

    /**
     * Current season number. Bump this (or run `/marshcore season new`) to start a new season, so every
     * player then gets the season-start action once, on their next join.
     */
    @JvmField var seasonId: Int = 1

    /** Season spawn: dimension id + position + facing. Set in-game with `/marshcore season setspawn`. */
    @JvmField var spawnDimension: String = "minecraft:overworld"
    @JvmField var spawnX: Double = 0.5
    @JvmField var spawnY: Double = 100.0
    @JvmField var spawnZ: Double = 0.5
    @JvmField var spawnYaw: Float = 0.0f
    @JvmField var spawnPitch: Float = 0.0f

    /** Shown once at season start (&-color codes; blank to disable). */
    @JvmField var seasonWelcomeMessage: String =
        "&d&lWelcome to the new season! &r&7You've been teleported to spawn."

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { GSON.toJson(this, it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun load(path: Path): MarshCoreConfig {
            if (Files.exists(path)) {
                runCatching {
                    Files.newBufferedReader(path).use { GSON.fromJson(it, MarshCoreConfig::class.java) }
                }.getOrNull()?.let { return it }
            }
            val fresh = MarshCoreConfig()
            runCatching { fresh.save(path) }
            return fresh
        }
    }
}
