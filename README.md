# CultAC

CultAC is an open source Minecraft anticheat designed to support the latest versions of Minecraft.

**This repository is a fork of upstream Grim.** It keeps the GPLv3 Grim 2.0-modern base
(checks, commands, config system, platform SPI) but replaces the PacketEvents dependency
with a raw-NMS (non-PacketEvents) networking pipeline, replaces the 2.0 prediction engine
with the 3.0 simulation engine written by DefineOutside in early 2023, and adds a dedicated Bedrock simulation engine bridged
through Geyser.

Discord: https://discord.gg/nyeVp7UFau

## Compatibility


* Java client support: 1.21.2 though 26.3
* Paper 1.21.2, 1.21.11, 26.2, or 26.3. Velocity is experimental


Bedrock notes
* GeyserFloatingPoints must be installed as a Geyser addon if players are > 3000 blocks from 0,0

If you run the Bukkit anticheat behind Velocity or BungeeCord:
- Geyser and Floodgate must be on the backend server so we may read the bedrock client's packets
- If you use ViaVersion, it must be installed on the backend server (where Cult is) ONLY.
  Cult does not support having ViaVersion installed on the proxy, even if it is also installed on the backend.

## Pull Requests

See [Contributing](CONTRIBUTING.md) for more information about contributing and what our guidelines
are.

## Developer Plugin API

CultAC preserves the published [GrimAPI](https://github.com/GrimAnticheat/GrimAPI)
packages, types, methods, Bukkit services, and events. Plugins compiled against
GrimAPI can keep their dependency on `GrimAC`; CultAC provides that plugin alias.
The implementation lives under `ac.cult.cultac`, while the external API stays under
`ac.grim.grimac.api`.

## Commands and configuration

`/cult` is the primary command. `/cultac`, `/grim`, and `/grimac` are aliases.
Permissions and placeholders use `cult` (for example, `cult.alerts` and `%cult_ping%`).
CultAC loads configuration from `plugins/CultAC/`. When upgrading an existing
installation, move your configuration/data directory from `plugins/GrimAC/` to
`plugins/CultAC/`, update `grim.*` permissions and `%grim_*%` placeholders, and
retain your configured database names and table mappings to keep existing data.
New defaults use CultAC branding. GrimAPI's logical storage IDs and legacy
migration schema names remain unchanged for compatibility.

## Updating without a restart

Install `bukkit/build/libs/CultAC-paper.jar` on Paper or
`velocity-bootstrap/build/libs/CultAC-velocity.jar` on Velocity once to enable the loader.
The native plugin stays loaded while each anticheat generation runs in its own classloader.

1. Copy a compatible plugin release or runtime jar into the plugin data directory's `updates/` folder
   (`plugins/CultAC/updates/` on Paper, `plugins/cultac/updates/` on Velocity).
2. Run `/cult update <filename.jar>` with `cult.update` permission.
3. Run `/cult runtimes` with `cult.runtimes` permission to see current and draining generations.

For development on Velocity, `/cult devswap` loads `plugins/cultac/updates/CultAC-velocity.jar`
and requires the separate `cult.devswap` permission. `/cult update` still requires a filename.
That fixed-name staging entry can be a symlink to the build output. Wait for the build to finish
before running the command; the loader snapshots each accepted version into its own immutable
generation directory. Shutdown cleanup preserves the symlink and its build target.

A version is the `plugin.yml` version (Velocity uses `velocity-plugin.json`) plus the runtime
jar's complete SHA-256 digest. Commands display the first twelve digest characters.
Two builds with the same descriptor version and different hashes are distinct versions;
an identical engine is not activated twice. A complete plugin release is identified by its
embedded engine, and its complete native jar is saved for installation on shutdown.

Existing Java and Bedrock connections remain on their original generation until disconnect.
New connections use the most recently activated generation. There is no fixed generation limit.
The bootstrap records connection and user ownership explicitly; commands, placeholders, and
GrimAPI handles use that same directory. Runtime ownership is not inferred from implementation
classloaders. One resource scope tracks calls, callbacks, and accepted queued work. The manager
alone cancels registrations, drains work, stops the engine, and closes its classloader.
An old generation unloads after its connections, queued packet work, callbacks, and native
registrations finish cleanup. Cleanup failures retain the generation and appear in `/cult runtimes`.
Cached GrimAPI user/check handles detach when their session ends; acquire a new user after reconnect.

`/cult reload` validates the configuration for every live generation before applying it.
Startup failure, a wrong platform/host version, or an incompatible `cult-loader-version`
rejects an update before publication. `cult-loader-version` is an explicit compatibility revision, configured with `cultLoaderVersion`
in `gradle.properties` and included in both the native plugin and runtime descriptors. Increment
it only for incompatible runtime/shared API changes. Compatible loader bug fixes keep the same
revision and do not block engine updates; the updated native loader takes effect on the next
normal shutdown/restart. An incompatible revision requires installing the complete plugin and
restarting once.
The current compatibility revision is `2`, covering the explicit ownership and lifetime SPI.

On shutdown, Cult installs the latest selected version into its installed plugin jar **before
final runtime unload**. For a runtime-only update it preserves the bootstrap and replaces the
embedded engine; for a complete release it installs the saved release jar. It verifies the
version and engine digest before an atomic replacement. After successful installation it
removes regular files from `updates/`; it leaves subdirectories and symlinks alone. If installation
fails, it retains both the immutable active artifact and staged updates, reports the failure,
and keeps the restart marker pointing at the selected engine.

Runtime-only artifacts are `bukkit-runtime/build/libs/CultAC-paper-runtime.jar` and
`velocity-platform/build/libs/CultAC-velocity-runtime.jar`. Put them in `updates/`, not directly
in the native `plugins/` folder. The complete distributable includes the bootstrap and its
initial runtime.


Loader unit tests: `./gradlew :reload-host:test :reload-services:test`.
The separate lifecycle smoke runner is `scripts/smoketests/hot-update-lifecycle.py`;
pass `--paper-jar <Paper 26.3 jar>` and optionally `--velocity-jar <Velocity jar>`.
It starts isolated loopback servers, holds real Java login connections across an update,
checks incompatibility rejection and retirement, verifies shutdown installation and cleanup,
and restarts each server to verify the selected hash. It performs ten updates by default
(`--iterations` changes that count). Run it separately with `--collect-loaders` to require
loader collection using `jcmd`. Only that collection run clears soft metadata caches on GC;
functional runs use normal JVM policy. Collection also waits for pending JIT compilation
to release its loader handles. Production needs no additional JVM flags.
Optional `--paper-geyser <jar>` and `--velocity-geyser <jar>` also start Geyser on
isolated loopback ports and check bridge startup and retirement.

`scripts/smoketests/hot-update-bedrock.py` reuses the existing BedrockPacketReplay CLI and
observer with the immutable `elytra`, `elytra2`, and `longcourse` corpus. Pass `--replay-root`,
`--scenarios`, `--paper-lock`, and optionally `--velocity-lock` for pinned native runtime
artifacts. It updates during continuously replaying longcourse baseline and seed-42 mutation
runs while holding a Java connection, then checks all three captures through three baseline
rounds and their mutations on the selected runtime. It preserves every replay assertion,
verifies shutdown installation/cleanup, and restarts the installed version. Artifacts are
saved under `build/hot-update-bedrock/` by default. Use distinct `--java-port` and
`--bedrock-port` values when other local servers are running.

## Compiling From Source

Install JDK 25 and Git, and set `JAVA_HOME` to the JDK 25 installation.

1. `git clone https://github.com/CultAC/CultAC.git`
2. `cd` into the cloned directory
3. `./gradlew shadowJar`
4. The final jar is at `bukkit/build/libs/`

Normal builds use the checked-in Bedrock collision catalog. Generator code is maintained
separately; [Geyser's mappings-generator](https://github.com/GeyserMC/mappings-generator) is the public upstream.

## Cult changes from Grim

* Dedicated simulation engine for bedrock players
  * Written and validated to 0.001 accuracy without exemption scenarios
    * Tridents, elytra, slime, honey, water, lava, bubble columns, etc. all match bedrock client
  * Machine generated based upon replaying bedrock packets sequences thousands of times, human validated
  * Listens to bedrock packets to increase accuracy
* Chunk section deduplication. Only one copy per unique chunk section is stored reducing memory usage in common areas.
  * Copy on write on shared chunk sections preserves packet-based blocks, version differences, and latency differences
  * Servers with many players in one area will see a substantial memory decrease
* NMS based packets rather than PacketEvents
* Lower allocation transaction scheduling
* Fork of Grim 3.0 engine
* Uses native collision from the server to reduce maintenance burden
* Uses native block placing logic to increase accuracy and reduce maintenance burden
* Uses client-provided inventory changes to increase accuracy and reduce maintenance burden
* Packet driven piston simulation
* FairReach ported from Grim 3.0 to limit latency abuse for reach
* Loss of Fabric and Spigot support. Fabric support may be re-added eventually

## What's not done
* Few additional checks compared to upstream Grim 2.0, the focus is on the bedrock and 3.0 simulation engine
* Extensive bedrock validation
* Some bedrock actions such as riptide aren't perfect, and some feature from the most recent versions of the game aren't implemented
* Reach support for bedrock players. The basis is the same as Java, but Geyser and Bedrock clients change a bunch of small things.
* Validation for non-latest bedrock versions.
* Validation on a variety of bedrock platforms, the primary target was bedrock clients running on Android via Linux
* Validation for bedrock block placing, breaking, interactions etc, especially on touchscreen

## Cult Supremacy

What makes Cult stand out against other anticheats?

### Movement Simulation Engine

* We have a 1:1 replication of the player's possible movements
    * This covers everything from basic walking, swimming, knockback, cobwebs, to bubble columns
    * It even covers riding entities from boats to pigs to striders
* Built upon covering edge cases to confirm accuracy
* The order of collisions depends on the client version and is correct
* Accounts for minor bounding box differences between client versions, for example:
    * Blocks that do not exist in the client's version use ViaVersion's replacement block
    * Block data that cannot be translated to previous versions is replaced correctly
    * All vanilla collision boxes have been implemented
* Bedrock players are simulated by a dedicated Bedrock engine validated against
  vanilla Bedrock client/server behavior, not exempted

### Fully asynchronous and multithreaded design

* All movement checks and the overwhelming majority of listeners run on the Netty thread
* Velocity decodes consumed values with stateless, isolated Via types for the protocol at its physical codecs.
  Java connections use their channel event loop; Bedrock connections use Geyser's tick loop.
* Paper and Velocity share a pinned, isolated ViaVersion/ViaBackwards library for numeric mappings and tag rewrites.
  It uses Via's directed paths and tag handlers without private player connections or exported rewrite tables.
* Vanilla holders keep their bootstrap defaults. Immutable connection snapshots supply tags and item defaults,
  and the isolated placement runtime reads tags from each request without a shared model lock.
* Older Velocity protocols resolve dynamic registry entries by name against the 26.3 defaults, while preserving
  received dimension types. Custom dynamic entries unavailable in those defaults are logged once.
* The anticheat can scale to many hundreds of players, if not more
* Thread safety is carefully thought out
* The next core allows for this design

### Full world replication

* The anticheat keeps a replica of the world for each player
* The replica is created by listening to chunk data packets, block places, and block changes
* On all versions, chunks are compressed to 16-64 kb per chunk using palettes
* Using this cache, the anticheat can safely access the world state
* Per player, the cache allows for multithreaded design
* Sending players fake blocks with packets is safe and does not lead to falses
* The world is recreated and deduplicated for each player to allow lag compensation
* Client sided blocks cause no issues with packet based blocks. Block glitching does not false the
  anticheat.

### Latency compensation

* World changes are queued until they reach the player
* This means breaking blocks under a player does not false the anticheat
* Everything from flying status to movement speed will be latency compensated

### Inventory compensation

* The player's inventory is tracked to prevent ghost blocks at high latency, and other errors

### Secure by design, not obscurity

* All systems are designed to be highly secure and mathematically impossible to bypass
* For example, the prediction engine knows all possible movements and cannot be bypassed

### Java packet logging

Use `/cult debug javapacketlog [username]` to start or stop a readable packet capture for a connected player;
without a username it captures the player running the command. The command requires `cult.debug` and also works through the `cultac`, `grim`, and `grimac` aliases.
Use `/cult debug javapacketlog <username> --onjoin` to capture the next connection for that Java-side profile name,
including the Floodgate prefix when applicable. Captures start when the authenticated profile is available,
covering configuration and play traffic.

Logs are saved under the plugin data folder in `javapacketlogs/`. They contain inbound packets before listener changes
and outbound packets in their emitted order, including Geyser's translated Java traffic. The logger uses existing
decoded fields and named empty packets; unread payloads are omitted and counted in the footer. Binary fields are
summarized by size, and item stacks show type and quantity. Headers identify the observed and model protocols.
Outbound records describe observed writes rather than client acknowledgements. Disconnecting stops the capture,
and the command reports the path after saving.

### Proxy tick diagnostics

Use `/cult debug proxyticks [player]` on Velocity to show the observed tick-end hit and miss percentages,
sample counts, total emitted tick ends, and estimator mode. The player defaults to yourself; console must
name a player. Requires `cult.debug`. Add `--reset` to start a new measurement for that connection.

A hit is a tracking wave followed by a proxy tick end. A miss is another update for the same tracked entity,
or a time synchronization starting the next tick, before the previous wave received a tick end. A late
corrective tick end still counts as a miss. In timer mode, a backend burst alone does not count as a hit.
Percentages cover observed tracking waves since connection or reset; empty tick ends do not improve them.
This is an estimate: ticks without tracking evidence and extra boundaries within one wave cannot be judged.
