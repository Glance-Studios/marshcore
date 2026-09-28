# MarshCore

Server-side systems for the MarshLands SMP, plus the client companion that talks to them.

```
/marshcore maintenance on|off
/marshcore season setspawn            set the season spawn to where you stand
/marshcore season new                 bump the season, re-arming the welcome for everyone
/marshcore season apply <player>      run the season-start action on one player
/marshcore update check               what version is required, and where it came from
/marshcore update mandatory on|off
```

## What it does

**Mending reset.** On a player's first login after it is enabled, Mending is stripped from every
item they carry - inventory, ender chest, and the contents of any shulker box, recursively. The
items survive; only the enchantment goes. They are compensated for the loss: a plain Mending book if
two or more items were stripped, or a golden equivalent of the item if exactly one was. It runs once
per player, ever.

**Season start.** When `seasonId` is bumped, the next time each player joins they are teleported to
the configured spawn and shown a welcome. Gated per player per season by a tag, so it fires once
each and not again.

**Maintenance mode.** A mixin on the status ping rewrites the version field, so the server list
shows your maintenance text in red and the client treats the server as protocol-incompatible. Anyone
who connects anyway gets the configured kick message.

**The pack-version gate.** The interesting one, below.

## Why the gate runs in the configuration phase

The updates that matter are the ones that change registry content, and an outdated client dies on
registry sync - before it reaches the play phase, and therefore before any chat warning could fire.
A join-time check is too late to be useful for exactly the updates it exists to catch.

So the check runs one phase earlier. On configuration start the server sends a version query and
holds the phase until the client answers. Up to date, the connection proceeds. Outdated and the
update is mandatory, the player gets a readable text wall instead of a packet error. Outdated and
it's soft, they are let in and get the chat warning instead.

A client with no marshcore-client installed never answers. That shows up as an unreachable channel
or a timeout, and both count as outdated.

The query carries the whole verdict - required version, URL, whether it is mandatory - so the client
knows it is out of date before the kick lands and can put a working update button on the disconnect
screen. The kick message itself cannot do that: vanilla renders the disconnect reason in a widget
with no mouse handling, so click and hover events on it are inert. That is also why the wall spells
the URL out rather than hiding it behind a link.

## Three modules, two jars

| Module | Goes where | Contains |
| --- | --- | --- |
| `marshcore-server` | the server's `mods/` | everything above |
| `marshcore-client` | the modpack | the version reporter, update button and PackPilot launcher |
| `marshcore-api` | nested inside both | the two payloads they speak |

The api module ships jar-in-jar inside each of the others rather than being compiled into both,
because Fabric mods share a classloader and duplicate classes would clash for anyone running both.

## The client side

`marshcore-client` reports its baked-in pack version on two channels - the configuration phase for
the gate, and again on join for the soft warning - and adds an update button to the kick screen.
The button also sidesteps the client's http/https-only rule for links, because it hands the URI to
the OS directly.

`PackPilotLauncher` finds and starts PackPilot, the pack installer, if it is present. Detection is
client-side by design - the server never names a path to execute - and runs once on a daemon thread
so it never blocks a frame. A configured path overrides it.

## Config (`config/marshcore.json`)

Notable keys:

| Key | Meaning |
| --- | --- |
| `mendingResetEnabled` | the one-time strip and compensation |
| `maintenanceEnabled` / `maintenanceVersionText` / `maintenanceKickMessage` | maintenance mode |
| `requiredPackVersion` | the version clients are checked against |
| `requiredPackMandatory` | whether a mismatch disconnects or just warns |
| `updateWallMessage` / `updateWallLinkText` / `updateUrl` | the disconnect screen |
| `updateGateTimeoutSeconds` | how long to wait for a client that may not answer (default 10) |
| `updateManifestUrl` / `updateManifestIntervalMinutes` | poll a remote `latest.json` instead of pinning |
| `seasonId` / `spawnDimension` / `spawnX,Y,Z` / `seasonWelcomeMessage` | season start |

The manifest, if set, carries `version`, an optional `message` and an optional `url`, and is polled
on a background daemon thread.

## Build

```
./gradlew build
```

## Requirements

Minecraft **26.2**, Fabric Loader **0.19.3+**, **Java 25**, plus
[Fabric API](https://modrinth.com/mod/fabric-api) `0.155.2+26.2` and
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) `1.13.12+kotlin.2.4.0`.

## Limits

The update check is a prompt, not a security boundary. It exists to tell players to update and to
keep an outdated client off a registry sync it would die on, and it is still being built out - treat
it as a convenience rather than something that keeps anyone out.

The Mending reset assumes everyone joins eventually - a player who never logs in again keeps their
Mending.
