# Bedrock engine hand-off

Velocity transfers the Geyser session and its Java channel together when the owning engine processes
`configuration_acknowledged`, before forwarding it to Velocity. Java uses its channel loop; Bedrock
uses Geyser's tick loop. The old engine removes its handlers, GFP adapter and latency receipts; the
host atomically changes both leases; the new engine installs its handlers within that same task.

The Geyser callback queue now lives in `reload-integrations`, so its class belongs to the host.
Retiring a tap clears its receipt callbacks, including callbacks already polled for dispatch, while
preserving native Geyser callbacks and every written FIFO slot. An old engine's marker therefore
consumes its own reply without acknowledging a new engine's marker.

The continuation includes the native session identity, runtime entity ID, forced movement mode and
rewind size, acknowledged collision definition, and anticipated/active loading screens. The last
accepted movement tick also survives, so an old auth input cannot become valid after the swap. GFP
retains its origin revision and origins captured for native packets awaiting Cloudburst writes. The transfer
map contains JDK values and opaque native packet/session handles; it contains no engine state objects.
Prediction starts without carry and is anchored through the existing dimension/teleport/chunk proof.
The new host callback and host-owned queue require bootstrap compatibility version 4; an older
bootstrap rejects these engine artifacts through the existing loader-version check.

## Source basis

The Gradle-cached `core-2.10.1-SNAPSHOT-sources.jar` was inspected before implementation:

- `JavaStartConfigurationTranslator.translate` sends empty chunks during reconfiguration.
- `GeyserSession.sendDownstreamGamePacket` requires `ProtocolState.GAME`; game traffic is dropped
  during configuration. Bedrock traffic still reaches Geyser's own translators, but supplies no
  backend movement for prediction while Java is configuring.
- `JavaLoginTranslator.translate` resends StartGame only for the initial spawn. For a spawned session,
  its temporary dimension switch forces a full switch even when both backends use the same dimension.
  It resets player metadata/attributes and inventories.
- `DimensionUtils.switchDimension` clears chunk/entity/effect caches and removes effects. Its
  dimension packet respawns at `(0,32767,0)` and resets motion before the backend's actual spawn.
- The forced reset is conditional on `session.isSpawned()`. A switch during an unfinished spawn
  retains the old engine until a later eligible acknowledgement rather than assuming a reset.
- MCP-Reborn 26.2 `ClientPacketListener.handleConfigurationStart` clears the client level, switches
  the inbound protocol, sends the acknowledgement in PLAY, then switches the outbound protocol.
  Cult's callback runs after its acknowledgement listeners and before the native decoder receives
  that PLAY terminal.
- [Mojang's loading-screen protocol documentation](https://github.com/Mojang/bedrock-protocol-docs/blob/main/json/ServerboundLoadingScreenPacket.json)
  requires anticipated starts and matching IDs. The existing gate and movement thresholds are preserved.

## Live replay

Use Java 25, the immutable capture corpus and a pinned Velocity runtime lock:

```sh
./gradlew :velocity-bootstrap:assemble
python3 scripts/smoketests/hot-update-bedrock-switch.py \
  --replay-root /path/to/BedrockPacketReplay \
  --scenarios /path/to/bedrock-smoketest-scenarios/scenarios \
  --velocity-lock /path/to/velocity-runtime-lock.json \
  --players 3 --java-peers 2 --transfer-delay 10 \
  --cases longcourse elytra elytra2
```

The script starts isolated loopback Paper backends with ViaVersion/ViaBackwards, identical captured
terrain and modern forwarding, plus Cult/Geyser on Velocity. Each tape uses persistent physical
Bedrock connections for three complete rounds: initial baseline, baseline after an update and switch
to backend B, and seed-42 mutations after another update and return to backend A. More rounds can
be selected with `--rounds`.

`--players` selects the number of simultaneous Bedrock clients; `--java-peers` holds additional Java
connections across updates. All players pin the old engine before playback starts. Playback starts
at staggered times, so remaining old-engine players continue genuine recorded movement while the
first players move to the new engine. `--transfer-delay` spaces individual transfers in seconds.
The Java peers transfer last, proving that the old engine also waits for their leases.

With three Bedrock players and two Java peers, each update starts with eight old leases. Its observed
old/new lease counts must progress through `6/2`, `4/4`, `2/6`, `1/7`, then `0/8`; the old engine may
unload only at the last step. Every Bedrock client finishes its complete final tape before the driver
allows any client to disconnect.

`LiveHandoffReplay` preserves the existing replay's complete per-frame assertions, terrain/teleport
barriers, mutation/correction checks and genuine captured warmup. It also requires real dimension
changes and exactly one StartGame. `HandoffReplayObserver` uses the existing passive observation
adapters and creates a fresh observation segment for each native user, because native user identity
changes on engine transfer. Its per-player control channels prevent one client's observation from
replacing another's. `HandoffReplayFixture` uses the original terrain/loadout helpers, with separate
worlds per player to preserve each immutable tape's physics while exercising concurrent sessions.
The Java peers extend the original lifecycle client's framing with the reconfiguration terminal.
These helpers do not edit the corpus or original validation tools.

The Python runner verifies both leases moving for each Bedrock transfer and one for each Java peer,
the exact remaining counts in both engines, and old-runtime unloading while all clients remain
connected. It polls runtime status until the asynchronous switch reaches the required counts.
Artifacts include console logs, wire events, prediction streams, per-round results, mutation plans,
the exact runtime lock and plugin SHA-256 under `build/hot-update-bedrock-switch/run-*`.

The pinned live runtime uses Geyser preview build 3057 (2.12.0), Paper 26.3 build 177, Velocity
4.2.1 and ViaVersion/ViaBackwards 5.12.1. GFP origins and queued emissions have focused unit coverage;
the default live rig does not install the optional GFP extension.

Compiling the helper fixture needs a Paper 26.3 API jar. The script finds it in the Gradle cache;
`--paper-api /path/to/paper-api.jar` supplies it explicitly on another machine.

## Recorded validation: 2026-10-13

[validated-run.json](validated-run.json) records the tested plugin/runtime hashes and results:
three Bedrock clients, two persistent Java peers, six update publications and 30 hand-offs, with
10-second intervals between transfers. All 27 complete replay rounds across `longcourse`, `elytra`
and `elytra2` passed (11,700 measured frames). The maximum untouched raw/adjusted position offset
was `0.0005035400390625`, below the unchanged `0.001` threshold. Mutation/correction assertions
passed after every final transfer. Every update followed the required lease sequence and retired
the old engine only after its last player moved.

The 111 focused host/transport/continuation tests passed. The broader common suite still has
11 failures among 1,095 tests; its offline subset has six failures among 206 tests. These involve
existing movement assertions and three missing small-scenario fixture files. No checks were
weakened or excluded to produce the live replay results.

Cloudburst emitted peer-close warnings when the replay clients intentionally disconnected at
the end. All session leases released, Geyser shut down successfully, and the final runtime unloaded.
