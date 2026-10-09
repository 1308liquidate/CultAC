#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cultac_root="$(cd "$script_dir/.." && pwd)"
harness="${SMOKETEST_REPO:-$cultac_root/.ignored/pr-2848/harness}"
[[ -x "$harness/gradlew" ]] || { echo "Set SMOKETEST_REPO to a prepared client harness." >&2; exit 1; }
harness="$(cd "$harness" && pwd)"
[[ -f "$harness/src/main/java/ac/grim/movementvalidation/Pr2848InventoryScenarios.java" ]] || {
  echo "Apply scripts/pr2848-inventory-smoketest.patch and scripts/pr2848-experimental-smoketest.patch in an isolated harness checkout." >&2
  exit 1
}
rg -q 'REAL_VALIDATION_EXPERIMENTAL_CHECKS' "$harness/src/main/java/ac/grim/movementvalidation/RealServerLauncher.java" || {
  echo "The harness needs scripts/pr2848-experimental-smoketest.patch." >&2
  exit 1
}
manifest="${SMOKETEST_WORKSPACE_MANIFEST:-$harness/.real-validation/smoketest-workspace.properties}"
[[ -f "$manifest" ]] || { echo "Set SMOKETEST_WORKSPACE_MANIFEST to a prepared client/server manifest." >&2; exit 1; }
artifacts="${SMOKETEST_ARTIFACT_ROOT:-$cultac_root/.ignored/pr2848-validation/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$artifacts"
artifacts="$(cd "$artifacts" && pwd)"

# Copy and hash the exact plugin under test so a concurrent build cannot replace it.
python3 - "$manifest" "$artifacts" "$cultac_root" "${SMOKETEST_PLUGIN_JAR:-}" <<'PY'
import hashlib
import shutil
import sys
from pathlib import Path
source, output, root, requested = sys.argv[1:]
candidates = list((Path(root) / "bukkit/build/libs").glob("cultac-bukkit-*.jar"))
if not requested and not candidates:
    raise SystemExit("Build :bukkit:shadowJar first or set SMOKETEST_PLUGIN_JAR")
jar = Path(requested).resolve() if requested else max(candidates, key=lambda p: p.stat().st_mtime)
target = Path(output) / "plugin.jar"
shutil.copyfile(jar, target)
lines = [line for line in Path(source).read_text().splitlines()
         if not line.startswith(("smoketest.grim.devJar=", "smoketest.cultac.repoRoot=", "smoketest.cult.sha256="))]
sha = hashlib.sha256(target.read_bytes()).hexdigest()
lines += [f"smoketest.grim.devJar={target}", f"smoketest.cultac.repoRoot={root}", f"smoketest.cult.sha256={sha}"]
(Path(output) / "workspace.properties").write_text("\n".join(lines) + "\n")
print(f"Pinned plugin SHA256: {sha}")
PY
export REAL_VALIDATION_EXPERIMENTAL_CHECKS=true
export REAL_VALIDATION_AUTO_JUMP=true
export REAL_VALIDATION_SERVER_JVM_XMS="${REAL_VALIDATION_SERVER_JVM_XMS:-512m}"
export REAL_VALIDATION_SERVER_JVM_HEAP="${REAL_VALIDATION_SERVER_JVM_HEAP:-1536m}"
selection_args=(-Psmoketest.suite="${SMOKETEST_SUITE:-inventory,core}")
for argument in "$@"; do
  if [[ "$argument" == -Psmoketest.scenarios=* ]]; then
    selection_args=()
    break
  fi
done
"$harness/gradlew" -p "$harness" smoketestRun -x smoketestCombatReach --no-daemon --console=plain --max-workers=2 \
  -I "$script_dir/smoketest-workspace.init.gradle" \
  -Psmoketest.workspaceManifest="$artifacts/workspace.properties" \
  -Psmoketest.artifactRoot="$artifacts" \
  "${selection_args[@]}" \
  -Psmoketest.basePort="${SMOKETEST_BASE_PORT:-26980}" \
  -Psmoketest.jvmHeap="${SMOKETEST_JVM_HEAP:-3g}" "$@"
