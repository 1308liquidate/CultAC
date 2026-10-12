#!/usr/bin/env python3
"""Reuse the existing three immutable Bedrock replays while updating native Cult runtimes."""
import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def replay(args, native, data, case, mode, destination, prefix, update=None):
    destination.mkdir(parents=True)
    output = (destination / 'runner.log').open('w')
    process = subprocess.Popen([
        'java', '-jar', str(args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0-replay-cli.jar'),
        'replay', '--scenario', str(args.scenarios / case), '--plugin-directory', str(data),
        '--artifacts', str(destination), '--mode', mode, '--seed', '42', '--port', str(args.bedrock_port),
        '--username-prefix', prefix,
    ], stdout=output, stderr=subprocess.STDOUT)
    try:
        if update:
            # Observe actual measured prediction before updating; never pause or alter input frames.
            lifecycle.wait_for(lambda: process.poll() is not None
                               or (destination / 'predictions.ndjson').is_file(), 60)
            assert process.poll() is None, 'Replay ended before its first prediction'
            update()
            assert process.poll() is None, 'Update did not overlap the physical Bedrock connection'
        process.wait(timeout=180)
        result = json.loads((destination / 'result.json').read_text())
        assert process.returncode == 0 and result['passed'], result.get('failure', 'Replay failed')
        print(destination.name + ': ' + case + '/' + mode + ' PASS', flush=True)
        return json.loads((destination / 'semantic.json').read_text()) if mode == 'baseline' else None
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=15)
        output.close()


def exercise(args, paper_tools, velocity_tools, platform, lock, root):
    capture = paper_tools.runtime(args.replay_root / 'runtime-lock.json')
    target = (paper_tools.replay_runtime(lock, capture) if platform == 'paper'
              else velocity_tools.target_runtime(lock, capture))
    for case in args.scenarios.iterdir():
        if case.is_dir():
            paper_tools.capture_runtime(case, capture)
    cache = root / 'downloads'
    cache.mkdir(parents=True)
    jars = {name: paper_tools.replay_artifact(value, cache) for name, value in target['artifacts'].items()}
    server = root / platform
    (server / 'plugins').mkdir(parents=True)
    native = backend = None
    bots = []
    plugin = args.paper_plugin if platform == 'paper' else args.velocity_plugin
    installed = server / 'plugins/CultAC.jar'
    shutil.copyfile(plugin, installed)
    try:
        if platform == 'paper':
            paper_tools.configure(server, args.bedrock_port, args.java_port)
            properties = server / 'server.properties'
            properties.write_text(properties.read_text().replace('max-players=2', 'max-players=8'))
            for key, name in [('geyser', 'Geyser-Spigot.jar'), ('floodgate', 'floodgate-spigot.jar'),
                              ('viaversion', 'ViaVersion.jar'), ('viabackwards', 'ViaBackwards.jar')]:
                shutil.copyfile(jars[key], server / 'plugins' / name)
            shutil.copyfile(args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0.jar',
                            server / 'plugins/BedrockPacketReplay.jar')
            data = server / 'plugins/BedrockPacketReplay'
            native_jar, prefix = jars['paper'], '.'
        else:
            backend_dir = root / 'backend'
            (backend_dir / 'plugins').mkdir(parents=True)
            velocity_tools.configure(backend_dir, server, args.java_port + 1, args.java_port, args.bedrock_port)
            properties = backend_dir / 'server.properties'
            properties.write_text(properties.read_text().replace('max-players=2', 'max-players=8'))
            for key, name in [('viaversion', 'ViaVersion.jar'), ('viabackwards', 'ViaBackwards.jar')]:
                shutil.copyfile(jars[key], backend_dir / 'plugins' / name)
            shutil.copyfile(args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0-fixture.jar',
                            backend_dir / 'plugins/BedrockReplayFixture.jar')
            shutil.copyfile(jars['geyser'], server / 'plugins/Geyser-Velocity.jar')
            shutil.copyfile(args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0-velocity.jar',
                            server / 'plugins/BedrockPacketReplay-velocity.jar')
            backend = lifecycle.Process(backend_dir, jars['paper'], True)
            data = server / 'plugins/bedrock-packet-replay'
            native_jar, prefix = jars['velocity'], ''
        native = lifecycle.Process(server, native_jar, platform == 'paper')
        lifecycle.wait_for(lambda: (data / 'ready.json').is_file())
        assert 'Failed to start Geyser' not in native.log(), 'Geyser listener failed'
        cult = server / ('plugins/CultAC' if platform == 'paper' else 'plugins/cultac')
        with ZipFile(installed) as archive:
            engine = cult / 'updates/source.jar'
            engine.write_bytes(archive.read('cult-runtime/default.jar'))
        current_hash = hashlib.sha256(engine.read_bytes()).hexdigest()
        # A second, native Java connection proves that retirement waits for both transports.
        java = lifecycle.Bot('CultBedrockPeer', args.java_port)
        bots.append(java)
        java.ready.wait(20)
        assert java.ready.is_set() and not java.closed
        transitions = []
        for iteration, mode in enumerate(('baseline', 'mutated')):
            old_hash = current_hash
            update = cult / ('updates/update-' + str(iteration) + '.jar')
            current_hash = lifecycle.updated_engine(engine, update, build=iteration + 100)

            def activate():
                offset = len(native.log())
                native.command('cult update ' + update.name)
                lifecycle.wait_for(lambda: 'Activated CultAC runtime' in native.log()[offset:])
                native.status(old_hash[:12] + '.*DRAINING sessions=3')
                native.status(current_hash[:12] + '.*CURRENT sessions=0')
                replacement = lifecycle.Bot('CultBedPeer' + str(iteration), args.java_port)
                bots.append(replacement)
                replacement.ready.wait(20)
                assert replacement.ready.is_set() and not replacement.closed
                transitions.append({'previousHash': old_hash, 'selectedHash': current_hash, 'mode': mode})

            replay(args, native, data, 'longcourse', mode, root / ('across-update-' + mode), prefix, activate)
            native.status(old_hash[:12] + '.*DRAINING sessions=1')
            offset = len(native.log())
            java.close()
            bots.remove(java)
            lifecycle.wait_for(lambda: 'Unloaded CultAC runtime' in native.log()[offset:])
            java = bots[-1]
            native.status(current_hash[:12] + '.*CURRENT sessions=1')

        reference = {}
        rounds = [('baseline-1', 'baseline'), ('baseline-2', 'baseline'),
                  ('baseline-after-other', 'baseline'), ('mutated', 'mutated')]
        for name, mode in rounds:
            for case in ('elytra', 'elytra2', 'longcourse'):
                semantic = replay(args, native, data, case, mode, root / case / name, prefix)
                if mode == 'baseline':
                    if case in reference:
                        assert semantic == reference[case], 'Semantic results changed between baseline rounds'
                    reference[case] = semantic
        native.stop()
        with ZipFile(installed) as archive:
            assert hashlib.sha256(archive.read('cult-runtime/default.jar')).hexdigest() == current_hash
        assert not list((cult / 'updates').iterdir())
        assert native.log().index('Installed CultAC') < native.log().rindex('Unloaded CultAC runtime')
        restarted = lifecycle.Process(server, native_jar, platform == 'paper')
        try:
            restarted.status(current_hash[:12] + '.*CURRENT sessions=0')
        finally:
            restarted.stop()
        (root / 'result.json').write_text(json.dumps({'passed': True, 'platform': platform,
                                                     'seed': 42, 'transitions': transitions}, indent=2))
        print(platform + ': Bedrock updates, corpus, mutations, promotion and restart PASS', flush=True)
    finally:
        for bot in bots:
            bot.close()
        if native and native.proc.poll() is None:
            native.stop()
        if backend and backend.proc.poll() is None:
            backend.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay-root', type=Path, required=True)
    parser.add_argument('--scenarios', type=Path, required=True)
    parser.add_argument('--paper-lock', type=Path, required=True)
    parser.add_argument('--velocity-lock', type=Path)
    parser.add_argument('--platform', choices=('paper', 'velocity', 'all'), default='all')
    parser.add_argument('--paper-plugin', type=Path, default=Path('bukkit/build/libs/CultAC-paper.jar'))
    parser.add_argument('--velocity-plugin', type=Path, default=Path('velocity-bootstrap/build/libs/CultAC-velocity.jar'))
    parser.add_argument('--artifact-root', type=Path, default=Path('build/hot-update-bedrock'))
    parser.add_argument('--java-port', type=int, default=26870)
    parser.add_argument('--bedrock-port', type=int, default=19770)
    args = parser.parse_args()
    for key in ('replay_root', 'scenarios', 'paper_lock', 'paper_plugin', 'velocity_plugin'):
        setattr(args, key, getattr(args, key).resolve())
    if args.velocity_lock:
        args.velocity_lock = args.velocity_lock.resolve()
    assert {p.name for p in args.scenarios.iterdir() if p.is_dir()} == {'elytra', 'elytra2', 'longcourse'}
    subprocess.run(['java', '-jar', str(args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0-replay-cli.jar'),
                    'validate', '--scenarios', str(args.scenarios)], check=True)
    args.artifact_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='run-', dir=args.artifact_root.resolve()))
    sys.path.insert(0, str(args.replay_root / 'scripts'))
    paper_tools = module('bedrock_paper', args.replay_root / 'scripts/run-movement-regressions.py')
    velocity_tools = module('bedrock_velocity', args.replay_root / 'scripts/run-velocity-movement-regressions.py')
    if args.platform == 'velocity' and not args.velocity_lock:
        parser.error('--platform velocity requires --velocity-lock')
    if args.platform != 'velocity':
        exercise(args, paper_tools, velocity_tools, 'paper', args.paper_lock, work / 'paper')
    if args.platform != 'paper' and args.velocity_lock:
        args.java_port += 2
        args.bedrock_port += 1
        exercise(args, paper_tools, velocity_tools, 'velocity', args.velocity_lock, work / 'velocity')
    print('Artifacts: ' + str(work), flush=True)


if __name__ == '__main__':
    lifecycle = module('lifecycle', Path(__file__).with_name('hot-update-lifecycle.py'))
    main()
