#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cultac_root="$(cd "$script_dir/.." && pwd)"
harness="${SMOKETEST_REPO:-$cultac_root/../mcp-client-smoketest}"
if [[ ! -x "$harness/gradlew" && -z "${SMOKETEST_REPO:-}" ]]; then
  harness="$cultac_root/../CultAC-old/mcp-client-smoketest"
fi
[[ -x "$harness/gradlew" ]] || { echo "Missing smoketest harness: $harness" >&2; exit 1; }
[[ -f "$harness/src/main/java/ac/grim/movementvalidation/AttackPowerClientProbe.java" ]] || {
  echo "Harness lacks the probe. Apply scripts/combat-attack-power-smoketest.patch in that harness." >&2
  exit 1
}
harness="$(cd "$harness" && pwd)"
artifacts="${SMOKETEST_ARTIFACT_ROOT:-$cultac_root/validation/combat-attack-power/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$artifacts"
artifacts="$(cd "$artifacts" && pwd)"
manifest="${SMOKETEST_WORKSPACE_MANIFEST:-$harness/.real-validation/smoketest-workspace.properties}"
[[ -f "$manifest" ]] || { echo "Missing prepared client/server manifest: $manifest" >&2; exit 1; }

# Pin the existing plugin binary and prepared client/server. This probe does not rebuild the anticheat.
python3 - "$manifest" "$artifacts/workspace.properties" "$cultac_root" "${SMOKETEST_PLUGIN_JAR:-}" <<'PY'
import hashlib
import sys
from pathlib import Path
source, target, root, requested = sys.argv[1:]
candidates = list((Path(root) / "bukkit/build/libs").glob("cultac-bukkit-*.jar"))
if not candidates:
    candidates = list((Path(root) / "validation/combat-attack-power/preserved-anticheat").glob("cultac-bukkit-*.jar"))
if not requested and not candidates:
    raise SystemExit("Missing existing anticheat jar; set SMOKETEST_PLUGIN_JAR to a prepared binary")
jar = Path(requested).resolve() if requested else max(candidates, key=lambda path: path.stat().st_mtime)
if not jar.is_file():
    raise SystemExit(f"Missing anticheat plugin: {jar}")
lines = [line for line in Path(source).read_text().splitlines()
         if not line.startswith(("smoketest.grim.devJar=", "smoketest.cultac.repoRoot=", "smoketest.cult.sha256=",
                                 "smoketest.lwjgl.nativeDir="))]
lines += [f"smoketest.grim.devJar={jar}", f"smoketest.cultac.repoRoot={root}",
          f"smoketest.cult.sha256={hashlib.sha256(jar.read_bytes()).hexdigest()}",
          f"smoketest.lwjgl.nativeDir={Path(target).resolve().parent / 'lwjgl-natives'}"]
Path(target).write_text("\n".join(lines) + "\n")
print(f"Anticheat binary: {jar}")
PY
export REAL_VALIDATION_SERVER_JVM_XMS="${REAL_VALIDATION_SERVER_JVM_XMS:-512m}"
export REAL_VALIDATION_SERVER_JVM_HEAP="${REAL_VALIDATION_SERVER_JVM_HEAP:-1536m}"
"$harness/gradlew" -p "$harness" smoketestCombatReach --no-daemon --max-workers=2 \
  -I "$script_dir/smoketest-workspace.init.gradle" \
  -Psmoketest.workspaceManifest="$artifacts/workspace.properties" \
  -Psmoketest.artifactRoot="$artifacts" \
  -Psmoketest.basePort="${SMOKETEST_BASE_PORT:-25880}" \
  -Psmoketest.jvmHeap="${SMOKETEST_JVM_HEAP:-3g}" "$@"
