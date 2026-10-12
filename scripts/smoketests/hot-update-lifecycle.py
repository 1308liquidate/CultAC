#!/usr/bin/env python3
"""Real 26.3 login/lifecycle checks; this does not validate client movement simulation.

Starts isolated loopback Paper and optional Velocity instances, then checks pinned
sessions, loader-version rejection, unload, shutdown promotion, and restart.
Requires Java 25 and a Paper 26.3 jar. Existing server directories are not modified.
"""
import argparse
import hashlib
import json
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import zlib
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

def vint(value):
    data = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        data.append(byte | (128 if value else 0))
        if not value:
            return bytes(data)

def readint(data, offset=0):
    value = 0
    for i in range(5):
        b = data[offset + i]
        value |= (b & 127) << 7 * i
        if b < 128:
            return (value, offset + i + 1)
    raise ValueError('Bad varint')

def text(value):
    data = value.encode()
    return vint(len(data)) + data
mapping = {}
for row in (Path(__file__).resolve().parents[2] / 'protocol/src/main/resources/ac/cult/cultac/protocol/data/777/index.tsv').read_text().splitlines():
    fields = row.split('\t')
    if fields[0] == 'packet':
        mapping[fields[1], fields[2], int(fields[3])] = fields[4].split(':')[-1]
ids = {(phase, direction, name): number for (phase, direction, number), name in mapping.items()}

class Bot:

    def __init__(self, name, port):
        self.name = name
        self.sock = socket.create_connection(('127.0.0.1', port))
        self.state = 'login'
        self.compress = False
        self.closed = False
        self.ready = threading.Event()
        self.sendraw(0, vint(777) + text('localhost') + struct.pack('>H', port) + vint(2))
        self.send('hello', text(name) + uuid.uuid3(uuid.NAMESPACE_DNS, name).bytes)
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def sendraw(self, number, data=b''):
        payload = vint(number) + data
        if self.compress:
            payload = vint(0) + payload
        self.sock.sendall(vint(len(payload)) + payload)

    def send(self, name, data=b''):
        self.sendraw(ids[self.state, 'serverbound', name], data)

    def exact(self, length):
        result = b''
        while len(result) < length:
            part = self.sock.recv(length - len(result))
            if not part:
                raise EOFError()
            result += part
        return result

    def read(self):
        try:
            while not self.closed:
                lengthBytes = b''
                while True:
                    byte = self.exact(1)
                    lengthBytes += byte
                    if byte[0] < 128:
                        break
                length, _ = readint(lengthBytes)
                payload = self.exact(length)
                if self.compress:
                    expanded, offset = readint(payload)
                    payload = payload[offset:]
                    if expanded:
                        payload = zlib.decompress(payload)
                number, offset = readint(payload)
                data = payload[offset:]
                name = mapping.get((self.state, 'clientbound', number), 'unknown')
                if name in ('disconnect', 'login_disconnect'):
                    raise RuntimeError('Server disconnect: ' + repr(data[:300]))
                if name == 'login_compression':
                    self.compress = True
                elif name == 'custom_query':
                    query, _ = readint(data)
                    self.send('custom_query_answer', vint(query) + b'\x00')
                elif name == 'login_finished':
                    self.send('login_acknowledged')
                    self.state = 'configuration'
                    self.send('client_information', text('en_us') + bytes([2, 0, 1, 127, 1, 0, 1, 0]))
                    self.send('custom_payload', text('minecraft:brand') + text('vanilla'))
                elif name == 'select_known_packs':
                    self.send('select_known_packs', vint(0))
                elif name == 'code_of_conduct':
                    self.send('accept_code_of_conduct')
                elif name == 'finish_configuration':
                    self.send('finish_configuration')
                    self.state = 'play'
                elif name == 'keep_alive':
                    self.send('keep_alive', data)
                elif name == 'ping':
                    self.send('pong', data)
                elif name == 'player_position':
                    teleport, offset = readint(data)
                    self.send('accept_teleportation', vint(teleport) + data[offset:offset + 24] + data[offset + 48:offset + 56])
                    self.send('player_loaded')
                    self.ready.set()
                    print(self.name + ' joined', flush=True)
        except BaseException as error:
            if not self.closed:
                print(self.name + ' error ' + repr(error), flush=True)
        finally:
            self.closed = True
            self.ready.set()

    def close(self):
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        self.thread.join(5)

def wait_for(predicate, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError('Timed out waiting for lifecycle condition')

class Process:

    def __init__(self, folder, jar, paper=False, collect=False):
        self.log_path = folder / ('console-' + str(len(list(folder.glob('console-*.log')))) + '.log')
        self.log_file = self.log_path.open('w')
        # The optional collection run clears soft metadata caches; functional runs use normal JVM policy.
        self.proc = subprocess.Popen(['java'] + (['-XX:SoftRefLRUPolicyMSPerMB=0'] if collect else []) + ['-Xms128m', '-Xmx1g', '-jar', str(jar)] + (['--nogui'] if paper else []), cwd=folder, stdin=subprocess.PIPE, stdout=self.log_file, stderr=subprocess.STDOUT, text=True)
        self.paper = paper
        wait_for(lambda: self.proc.poll() is not None or 'Done (' in self.log())
        if self.proc.poll() is not None:
            raise AssertionError('Startup failed: ' + str(self.log_path))

    def log(self):
        return self.log_path.read_text(errors='replace')

    def command(self, command):
        self.proc.stdin.write(command + '\n')
        self.proc.stdin.flush()

    def status(self, pattern):
        offset = len(self.log())
        self.command('cult runtimes')
        wait_for(lambda: re.search(pattern, self.log()[offset:]) is not None, 20)

    def stop(self):
        if self.proc.poll() is None:
            self.command('stop' if self.paper else 'shutdown')
            self.proc.wait(timeout=60)
        self.log_file.close()
        if self.proc.returncode:
            raise AssertionError('Process failed: ' + str(self.log_path))

def paper_folder(folder, port, cache):
    folder.mkdir()
    (folder / 'plugins').mkdir()
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text(f"""server-ip=127.0.0.1
server-port={port}
online-mode=false
white-list=false
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:dirt","height":3}},{{"block":"minecraft:grass_block","height":1}}],"biome":"minecraft:plains"}}
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
""")
    if cache:
        for name in ('libraries', 'cache', 'versions'):
            source = cache / name
            if source.is_dir():
                (folder / name).symlink_to(source, target_is_directory=True)

def updated_engine(original, destination, incompatible=False, build=0):
    with ZipFile(original) as source, ZipFile(destination, 'w', compression=ZIP_DEFLATED) as output:
        for entry in source.infolist():
            value = source.read(entry.filename)
            if incompatible and entry.filename == 'plugin.yml':
                value = re.sub(b'cult-loader-version:.*', b"cult-loader-version: '" + str(int(re.search(rb"cult-loader-version: *[\'\"]?([0-9]+)", value)[1]) + 1).encode() + b"'", value)
            if incompatible and entry.filename == 'velocity-plugin.json':
                descriptor = json.loads(value)
                descriptor['cult-loader-version'] = str(int(descriptor['cult-loader-version']) + 1)
                value = json.dumps(descriptor).encode()
            output.writestr(entry, value)
        output.writestr('lifecycle-test-build.txt', f'Same plugin version, different engine hash, build {build}.\n')
    return hashlib.sha256(destination.read_bytes()).hexdigest()

def exercise(folder, native_jar, plugin, platform, port, iterations=10, collect=False):
    installed = folder / 'plugins/CultAC.jar'
    shutil.copyfile(plugin, installed)
    native = Process(folder, native_jar, platform == 'paper', collect)
    restart = None
    bots = []
    try:
        geyser = bool(list((folder / 'plugins').glob('Geyser-*.jar')))
        if geyser:
            wait_for(lambda: 'Started Geyser on 127.0.0.1:' in native.log())
            wait_for(lambda: 'Geyser Bedrock movement bridge enabled' in native.log())
        data = folder / ('plugins/CultAC' if platform == 'paper' else 'plugins/cultac')
        with ZipFile(installed) as release:
            old_hash = hashlib.sha256(release.read('cult-runtime/default.jar')).hexdigest()
            engine = data / 'updates/engine-source.jar'
            engine.write_bytes(release.read('cult-runtime/default.jar'))
        old = Bot('CultOld', port)
        bots.append(old)
        old.ready.wait(20)
        assert old.ready.is_set() and (not old.closed), 'Old connection failed'
        native.status(old_hash[:12] + '.*CURRENT sessions=1')
        bad = data / 'updates/incompatible.jar'
        updated_engine(engine, bad, True)
        offset = len(native.log())
        native.command('cult update incompatible.jar')
        wait_for(lambda: 'requires cult-loader-version' in native.log()[offset:].lower())
        native.status(old_hash[:12] + '.*CURRENT sessions=1')
        for iteration in range(iterations):
            offset = len(native.log())
            update = data / ('updates/latest-' + str(iteration) + '.jar')
            new_hash = updated_engine(engine, update, build=iteration)
            if platform == 'velocity':
                complete = data / ('updates/release-' + str(iteration) + '.jar')
                with ZipFile(plugin) as source, ZipFile(complete, 'w', compression=ZIP_DEFLATED) as output:
                    for entry in source.infolist():
                        output.writestr(entry, update.read_bytes() if entry.filename == 'cult-runtime/default.jar' else source.read(entry.filename))
                    output.writestr('compatible-loader-fix.txt', 'Loader revision remains unchanged.\n')
                update = complete
            native.command('cult update ' + update.name)
            wait_for(lambda: 'Activated CultAC runtime' in native.log()[offset:])
            if geyser:
                wait_for(lambda: native.log().count('Geyser Bedrock movement bridge enabled') >= iteration + 2)
            new = Bot('CultNew' + str(iteration), port)
            bots.append(new)
            new.ready.wait(20)
            assert new.ready.is_set() and (not new.closed), 'New connection failed'
            native.status(old_hash[:12] + '.*DRAINING sessions=1')
            native.status(new_hash[:12] + '.*CURRENT sessions=1')
            offset = len(native.log())
            native.command('cult reload')
            wait_for(lambda: 'Config has been reloaded' in native.log()[offset:])
            old.close()
            bots.remove(old)
            wait_for(lambda: 'Unloaded CultAC runtime' in native.log()[offset:])
            native.status(new_hash[:12] + '.*CURRENT sessions=1')
            old, old_hash = new, new_hash
        if collect:
            assert shutil.which('jcmd'), 'Collection validation requires jcmd'
            # HotSpot CompileTask promotes its weak loader handle to a JNI global
            # during compilation. Give those native jobs time to release it.
            def collected():
                subprocess.run(['jcmd', str(native.proc.pid), 'GC.run'], check=True, capture_output=True)
                stats = subprocess.run(['jcmd', str(native.proc.pid), 'VM.classloader_stats'], check=True, capture_output=True, text=True).stdout
                (folder / 'classloaders-after-retirement.txt').write_text(stats)
                if stats.count('ac.cult.reload.host.RuntimeClassLoader') == 1:
                    return True
                time.sleep(1)
                return False
            wait_for(collected, 60)
            histogram = subprocess.run(['jcmd', str(native.proc.pid), 'GC.class_histogram'], check=True, capture_output=True, text=True).stdout
            (folder / 'heap-histogram-after-retirement.txt').write_text(histogram)
        native.stop()
        with ZipFile(installed) as promoted:
            assert hashlib.sha256(promoted.read('cult-runtime/default.jar')).hexdigest() == new_hash
        assert not list((data / 'updates').iterdir()), 'Staged update files remain'
        log = native.log()
        assert log.index('Installed CultAC') < log.rindex('Unloaded CultAC runtime')
        assert 'ClassCastException' not in log and 'CultAC failure in' not in log and 'cleanup failed' not in log
        restart = Process(folder, native_jar, platform == 'paper', collect)
        restart.status(new_hash[:12] + '.*CURRENT sessions=0')
        if geyser:
            wait_for(lambda: 'Started Geyser on 127.0.0.1:' in restart.log())
            wait_for(lambda: 'Geyser Bedrock movement bridge enabled' in restart.log())
        restart.stop()
        print(platform + ': pinned sessions, rejection, promotion, cleanup, restart PASS', flush=True)
    finally:
        for bot in bots:
            bot.close()
        if restart is not None and restart.proc.poll() is None:
            restart.stop()
        if native.proc.poll() is None:
            native.stop()

def install_geyser(folder, jar, platform, port):
    if not jar:
        return
    name = 'Geyser-Spigot' if platform == 'paper' else 'Geyser-Velocity'
    shutil.copyfile(jar, folder / 'plugins' / (name + '.jar'))
    config = folder / 'plugins' / name / 'config.yml'
    config.parent.mkdir()
    config.write_text(f"""bedrock:
  address: 127.0.0.1
  port: {port}
  transport: raknet
  clone-remote-port: false
  signaling:
    mode: none
java:
  auth-type: offline
config-version: 8
""")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--paper-jar', required=True, type=Path)
    parser.add_argument('--paper-cache', type=Path, help='Optional existing Paper cache; linked for read reuse.')
    parser.add_argument('--paper-plugin', type=Path, default=Path('bukkit/build/libs/CultAC-paper.jar'))
    parser.add_argument('--paper-geyser', type=Path, help='Optional Geyser-Spigot jar for bridge lifecycle checks.')
    parser.add_argument('--velocity-jar', type=Path)
    parser.add_argument('--velocity-plugin', type=Path, default=Path('velocity-bootstrap/build/libs/CultAC-velocity.jar'))
    parser.add_argument('--velocity-geyser', type=Path, help='Optional Geyser-Velocity jar for bridge lifecycle checks.')
    parser.add_argument('--artifact-root', type=Path, default=Path('build/hot-update-lifecycle'))
    parser.add_argument('--base-port', type=int, default=25890)
    parser.add_argument('--iterations', type=int, default=10)
    parser.add_argument('--collect-loaders', action='store_true', help='Separate collection test with controlled soft-cache GC policy.')
    args = parser.parse_args()
    if args.iterations < 1:
        parser.error('--iterations must be positive')
    args.artifact_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='run-', dir=args.artifact_root.resolve()))
    paper = work / 'paper'
    paper_folder(paper, args.base_port, args.paper_cache.resolve() if args.paper_cache else None)
    install_geyser(paper, args.paper_geyser, 'paper', args.base_port + 3)
    exercise(paper, args.paper_jar.resolve(), args.paper_plugin.resolve(), 'paper', args.base_port, args.iterations, args.collect_loaders)
    if args.velocity_jar:
        backend = work / 'backend'
        paper_folder(backend, args.base_port + 2, args.paper_cache.resolve() if args.paper_cache else None)
        server = Process(backend, args.paper_jar.resolve(), True)
        try:
            proxy = work / 'velocity'
            proxy.mkdir()
            (proxy / 'plugins').mkdir()
            (proxy / 'velocity.toml').write_text(f"""config-version="2.7"
bind="127.0.0.1:{args.base_port + 1}"
online-mode=false
force-key-authentication=false
player-info-forwarding-mode="NONE"
[servers]
test="127.0.0.1:{args.base_port + 2}"
try=["test"]
[forced-hosts]
"lobby.example.com"=["test"]
"factions.example.com"=["test"]
"minigames.example.com"=["test"]
[advanced]
login-ratelimit=0
""")
            install_geyser(proxy, args.velocity_geyser, 'velocity', args.base_port + 4)
            exercise(proxy, args.velocity_jar.resolve(), args.velocity_plugin.resolve(), 'velocity', args.base_port + 1, args.iterations, args.collect_loaders)
        finally:
            server.stop()
    print('Artifacts: ' + str(work), flush=True)
if __name__ == '__main__':
    main()
