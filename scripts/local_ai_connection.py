#!/usr/bin/env python3
"""Prepare and supervise an authenticated, loopback-only AI SSH connection.

No AI key, photo or database is copied to another computer. The teammate sets
their own private AI environment and runs ai_teammate_probe.py there.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

SSH = '/usr/bin/ssh'
KEYGEN = '/usr/bin/ssh-keygen'
KEYSCAN = '/usr/bin/ssh-keyscan'
LOCAL_AI = 18001
CALLBACK = 8083


class ConnectionError(Exception):
    pass


def private_path(path, directory=False):
    path = Path(path).absolute()
    for parent in [path] + list(path.parents):
        if parent.is_symlink():
            raise ConnectionError('SYMLINK_NOT_ALLOWED')
    info = path.stat()
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise ConnectionError('PRIVATE_OWNER_PERMISSIONS_REQUIRED')
    if (directory and not stat.S_ISDIR(info.st_mode)) or (not directory and not stat.S_ISREG(info.st_mode)):
        raise ConnectionError('INVALID_FILE_TYPE')
    return path


def write_private(path, value, exclusive=False):
    path = Path(path)
    private_path(path.parent, directory=True)
    if path.exists() or path.is_symlink():
        private_path(path)
    if exclusive:
        fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as out:
            out.write(value)
        return
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as out:
            out.write(value)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def read_json(path):
    path = private_path(path)
    if path.stat().st_size > 32768:
        raise ConnectionError('CONFIG_TOO_LARGE')
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ConnectionError('CONFIG_MUST_BE_OBJECT')
    return value


def prepare(runtime):
    runtime = Path(runtime).absolute()
    if not runtime.exists():
        runtime.mkdir(mode=0o700, parents=True)
    private_path(runtime, directory=True)
    config_path = runtime / 'ai-connection.json'
    # Refuse partial overwrites as well as replacing an existing identity.
    paths = [config_path, runtime / 'ai_ed25519', runtime / 'ai_ed25519.pub', runtime / 'ai_known_hosts']
    if any(p.exists() or p.is_symlink() for p in paths):
        raise ConnectionError('CONNECTION_PREPARATION_ALREADY_EXISTS')
    result = subprocess.run([KEYGEN, '-q', '-t', 'ed25519', '-N', '', '-C', 'metaround-local-ai',
                             '-f', str(paths[1])], capture_output=True, timeout=15)
    if result.returncode:
        raise ConnectionError('DEDICATED_KEY_GENERATION_FAILED')
    for p in paths[1:3]:
        os.chmod(p, 0o600)
    write_private(paths[3], '', exclusive=True)
    config = {'format': 1, 'sshHost': '', 'sshUser': '', 'sshPort': 22,
              'expectedHostFingerprint': '', 'remoteAiPort': 8000,
              'remoteForwardLoopbackOnlyConfirmed': False,
              'localAiPort': LOCAL_AI, 'remoteCallbackPort': CALLBACK,
              'identityFile': str(paths[1]), 'knownHostsFile': str(paths[3])}
    write_private(config_path, json.dumps(config, indent=2) + '\n', exclusive=True)
    return {'prepared': True, 'configPath': str(config_path), 'publicKeyPath': str(paths[2]),
            'privateKeyTransferred': False, 'remoteActions': 0}


def configuration(runtime, complete=True):
    runtime = private_path(runtime, directory=True)
    c = read_json(runtime / 'ai-connection.json')
    expected = {'format', 'sshHost', 'sshUser', 'sshPort', 'expectedHostFingerprint',
                'remoteAiPort', 'localAiPort', 'remoteCallbackPort', 'identityFile', 'knownHostsFile',
                'remoteForwardLoopbackOnlyConfirmed'}
    if set(c) != expected or type(c['format']) is not int or c['format'] != 1:
        raise ConnectionError('INVALID_CONFIG_FIELDS')
    if any(not isinstance(c[n], str) for n in ['sshHost', 'sshUser', 'expectedHostFingerprint', 'identityFile', 'knownHostsFile']):
        raise ConnectionError('INVALID_CONFIG_FIELD_TYPES')
    if type(c['remoteForwardLoopbackOnlyConfirmed']) is not bool:
        raise ConnectionError('INVALID_REMOTE_BIND_CONFIRMATION')
    for name in ['sshPort', 'remoteAiPort', 'localAiPort', 'remoteCallbackPort']:
        if type(c[name]) is not int or not 1024 <= c[name] <= 65535:
            if name != 'sshPort' or type(c[name]) is not int or not 1 <= c[name] <= 65535:
                raise ConnectionError('INVALID_PORT')
    if c['localAiPort'] != LOCAL_AI or c['remoteCallbackPort'] != CALLBACK:
        raise ConnectionError('ONLY_ISOLATED_INTEGRATION_PORTS_ALLOWED')
    if c['remoteAiPort'] == CALLBACK:
        raise ConnectionError('REMOTE_PORT_COLLISION')
    missing = [n for n in ['sshHost', 'sshUser', 'expectedHostFingerprint'] if not c[n]]
    if not c['remoteForwardLoopbackOnlyConfirmed']:
        missing.append('remoteForwardLoopbackOnlyConfirmed')
    if complete and missing:
        raise ConnectionError('MISSING_' + '_'.join(missing))
    if c['sshHost'] and (not isinstance(c['sshHost'], str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.:-]{0,252}', c['sshHost'])):
        raise ConnectionError('INVALID_SSH_HOST')
    if c['sshUser'] and (not isinstance(c['sshUser'], str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]{0,63}', c['sshUser'])):
        raise ConnectionError('INVALID_SSH_USER')
    if c['expectedHostFingerprint'] and not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', c['expectedHostFingerprint']):
        raise ConnectionError('INVALID_HOST_FINGERPRINT')
    for name in ['identityFile', 'knownHostsFile']:
        p = Path(c[name])
        if not p.is_absolute() or p.parent != runtime:
            raise ConnectionError('IDENTITY_FILES_MUST_BELONG_TO_RUNTIME')
        private_path(p)
    return runtime, c, missing


def fingerprints(path):
    r = subprocess.run([KEYGEN, '-l', '-E', 'sha256', '-f', str(path)], capture_output=True, text=True, timeout=10)
    if r.returncode:
        raise ConnectionError('HOST_KEY_FILE_INVALID_OR_EMPTY')
    return [line.split()[1] for line in r.stdout.splitlines() if len(line.split()) >= 2]


def trust_host(runtime):
    runtime, c, _ = configuration(runtime)
    if Path(c['knownHostsFile']).stat().st_size:
        raise ConnectionError('EXISTING_HOST_TRUST_NOT_OVERWRITTEN')
    # Scanned keys are untrusted until compared with the independently supplied fingerprint.
    r = subprocess.run([KEYSCAN, '-T', '5', '-p', str(c['sshPort']), '-t', 'ed25519', c['sshHost']],
                       capture_output=True, text=True, timeout=15)
    lines = [line for line in r.stdout.splitlines() if line and not line.startswith('#')]
    if r.returncode or not lines or len(r.stdout) > 32768:
        raise ConnectionError('HOST_KEY_SCAN_FAILED')
    fd, name = tempfile.mkstemp(prefix='.untrusted-host-', dir=str(runtime))
    try:
        with os.fdopen(fd, 'w') as out:
            out.write('\n'.join(lines) + '\n')
        found = fingerprints(name)
        if len(found) != 1 or found[0] != c['expectedHostFingerprint']:
            raise ConnectionError('HOST_FINGERPRINT_MISMATCH')
        write_private(c['knownHostsFile'], '\n'.join(lines) + '\n')
    finally:
        Path(name).unlink(missing_ok=True)
    return {'hostTrustVerified': True, 'keyTransferred': False, 'remoteLoginAttempted': False}


def ssh_argv(c):
    return [SSH, '-F', '/dev/null', '-N', '-T', '-p', str(c['sshPort']),
            '-i', c['identityFile'], '-o', 'IdentitiesOnly=yes', '-o', 'IdentityAgent=none',
            '-o', 'BatchMode=yes', '-o', 'PasswordAuthentication=no', '-o', 'KbdInteractiveAuthentication=no',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + c['knownHostsFile'],
            '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'ForwardAgent=no', '-o', 'PermitLocalCommand=no',
            '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none', '-o', 'ExitOnForwardFailure=yes',
            '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=2',
            '-o', 'LogLevel=ERROR', '-L', '127.0.0.1:%d:127.0.0.1:%d' % (LOCAL_AI, c['remoteAiPort']),
            '-R', '127.0.0.1:%d:127.0.0.1:%d' % (CALLBACK, CALLBACK),
            c['sshUser'] + '@' + c['sshHost']]


def listening(port):
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(('127.0.0.1', port)) == 0


def process_snapshot(pid):
    if type(pid) is not int or pid < 2:
        return None
    r = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'lstart=', '-o', 'command='], capture_output=True, text=True, timeout=5)
    value = r.stdout.strip()
    return value if r.returncode == 0 and value else None


def owned_record(runtime):
    path = Path(runtime) / 'ai-ssh-process.json'
    if not path.exists():
        return None, False
    r = read_json(path)
    snapshot = process_snapshot(r.get('pid'))
    expected_command = ' /usr/bin/ssh -F /dev/null -N -T '
    owned = bool(snapshot and snapshot == r.get('processSnapshot') and expected_command in snapshot
                 and '-L 127.0.0.1:18001:127.0.0.1:' in snapshot
                 and '-R 127.0.0.1:8083:127.0.0.1:8083' in snapshot)
    return r, owned


def require_owned_tunnel(runtime):
    """Authenticate the configured SSH process and its actual local listener.

    A matching process snapshot alone does not establish who owns port 18001.
    Call immediately before sending an internal credential through that port.
    """
    try:
        runtime, c, _ = configuration(runtime)
        record, owned = owned_record(runtime)
        if not owned or not isinstance(record, dict) or type(record.get('pid')) is not int or record['pid'] < 2:
            raise ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED')
        args = ssh_argv(c)
        if record.get('argvSha256') != hashlib.sha256(json.dumps(args).encode()).hexdigest():
            raise ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED')
        if fingerprints(c['knownHostsFile']) != [c['expectedHostFingerprint']]:
            raise ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED')
        pid = record['pid']
        identity = subprocess.run(['/bin/ps', '-ww', '-p', str(pid), '-o', 'uid=', '-o', 'args='],
                                  capture_output=True, text=True, timeout=5,
                                  env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'})
        fields = identity.stdout.strip().split(None, 1)
        if (identity.returncode or len(fields) != 2 or fields[0] != str(os.getuid()) or
                fields[1] != ' '.join(args) or os.getpgid(pid) != pid):
            raise ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED')
        lsof = '/usr/sbin/lsof' if Path('/usr/sbin/lsof').is_file() else shutil.which('lsof')
        if not lsof:
            raise ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED')
        listeners = subprocess.run([lsof, '-nP', '-a', '-iTCP:18001', '-sTCP:LISTEN', '-Fp'],
                                   capture_output=True, text=True, timeout=5,
                                   env={'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'LC_ALL': 'C'})
        pids = {int(line[1:]) for line in listeners.stdout.splitlines() if re.fullmatch(r'p[0-9]+', line)}
        if listeners.returncode or pids != {pid}:
            raise ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED')
        return record
    except Exception:
        # No hostname, fingerprint, environment value, or subprocess output.
        raise ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED') from None


def start(runtime):
    runtime, c, _ = configuration(runtime)
    if fingerprints(c['knownHostsFile']) != [c['expectedHostFingerprint']]:
        raise ConnectionError('HOST_TRUST_NOT_VERIFIED')
    old, owned = owned_record(runtime)
    if owned:
        require_owned_tunnel(runtime)
        return {'started': False, 'alreadyRunning': True, 'managed': True}
    if old and process_snapshot(old.get('pid')):
        raise ConnectionError('UNOWNED_PID_RECORD_NOT_REPLACED')
    if listening(LOCAL_AI):
        raise ConnectionError('LOCAL_AI_PORT_ALREADY_IN_USE')
    if not listening(CALLBACK):
        raise ConnectionError('INTEGRATION_BACKEND_NOT_LISTENING')
    args = ssh_argv(c)
    log_path = runtime / 'ai-ssh.log'
    if log_path.exists() or log_path.is_symlink():
        private_path(log_path)
    fd = os.open(str(log_path), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    env = {'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C'}
    try:
        with os.fdopen(fd, 'ab') as log:
            proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=log, env=env, start_new_session=True)
        snapshot = process_snapshot(proc.pid)
        if not snapshot:
            raise ConnectionError('SSH_START_FAILED_DETAILS_IN_PRIVATE_LOG')
        record = {'format': 1, 'pid': proc.pid, 'processSnapshot': snapshot,
                  'argvSha256': hashlib.sha256(json.dumps(args).encode()).hexdigest()}
        write_private(runtime / 'ai-ssh-process.json', json.dumps(record, indent=2) + '\n')
        for _ in range(60):
            if proc.poll() is not None:
                raise ConnectionError('SSH_CONNECTION_FAILED_DETAILS_IN_PRIVATE_LOG')
            if listening(LOCAL_AI):
                require_owned_tunnel(runtime)
                return {'started': True, 'managed': True, 'portForwardingOnly': True,
                        'aiReadyVerified': False, 'reversePhotoDownloadVerified': False,
                        'callbackPostVerified': False, 'keyTransferred': False}
            time.sleep(0.2)
        stop(runtime)
        raise ConnectionError('SSH_FORWARD_START_TIMEOUT')
    except BaseException:
        # Do not leave our failed startup process alive.
        if 'proc' in locals() and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        raise


def stop(runtime):
    runtime = private_path(runtime, directory=True)
    record, owned = owned_record(runtime)
    if not record:
        return {'stopped': False, 'notRunning': True}
    if not owned:
        if process_snapshot(record.get('pid')):
            raise ConnectionError('REFUSE_TO_STOP_UNOWNED_PROCESS')
        return {'stopped': False, 'notRunning': True, 'staleRecordRetained': True}
    os.kill(record['pid'], signal.SIGTERM)
    for _ in range(50):
        if process_snapshot(record['pid']) != record['processSnapshot']:
            return {'stopped': True, 'onlyOwnedSshProcessSignalled': True}
        time.sleep(0.1)
    if process_snapshot(record['pid']) == record['processSnapshot']:
        os.kill(record['pid'], signal.SIGKILL)
    return {'stopped': True, 'onlyOwnedSshProcessSignalled': True}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def backend_ai_key(runtime):
    path = private_path(Path(runtime) / 'real-integration.env')
    if path.stat().st_size > 65536:
        raise ConnectionError('BACKEND_ENV_TOO_LARGE')
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.lstrip().startswith('#'):
            continue
        if '=' not in line:
            raise ConnectionError('INVALID_BACKEND_ENV')
        key, value = line.split('=', 1)
        if key in values:
            raise ConnectionError('DUPLICATE_BACKEND_ENV_KEY')
        values[key] = value
    if (values.get('AI_SERVER_BASE_URL') != 'http://127.0.0.1:18001' or
            values.get('APP_BACKEND_BASE_URL') != 'http://127.0.0.1:8083' or
            len(values.get('AI_SERVICE_KEY', '').encode()) < 32):
        raise ConnectionError('BACKEND_CHANNEL_MISMATCH')
    return values['AI_SERVICE_KEY']


def get_json(url, key=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    headers = {'Accept': 'application/json'}
    if key:
        headers['X-AI-Service-Key'] = key
    try:
        with opener.open(urllib.request.Request(url, headers=headers, method='GET'), timeout=5) as response:
            raw = response.read(65537)
            if response.status != 200 or len(raw) > 65536:
                raise ConnectionError('READINESS_RESPONSE_INVALID')
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ConnectionError('READINESS_RESPONSE_INVALID')
            return value
    except ConnectionError:
        raise
    except Exception:
        raise ConnectionError('READINESS_UNREACHABLE_OR_REJECTED') from None


def check(runtime):
    runtime, _, missing = configuration(runtime, complete=False)
    result = {'configured': not missing, 'missingFields': missing,
              'backendReady': False, 'aiReady': False, 'keyTransferred': False,
              'verificationScope': 'backend host and authenticated readiness through the owned SSH connection only',
              'realInferenceVerified': False, 'remoteListenerBindIndependentlyVerified': False,
              'aiToBackendPhotoReadVerified': False, 'callbackPostVerified': False, 'endToEndVerified': False}
    result['managedSshRunning'] = False
    for name, url in [('backend', 'http://127.0.0.1:8083/actuator/health/readiness'),
                      ('ai', 'http://127.0.0.1:18001/api/health/ready')]:
        try:
            key = None
            if name == 'ai':
                # Verify before even reading the key, then recheck immediately
                # before the authenticated request in case the listener changed.
                require_owned_tunnel(runtime)
                key = backend_ai_key(runtime)
                require_owned_tunnel(runtime)
                result['managedSshRunning'] = True
            data = get_json(url, key)
            checks = data.get('checks')
            result[name + 'Ready'] = (data.get('status') == 'UP') if name == 'backend' else (
                data.get('status') == 'ready' and isinstance(checks, dict) and
                all(checks.get(k) is True for k in ['worker', 'model', 'database']))
        except ConnectionError:
            result[name + 'CheckError'] = 'UNAVAILABLE_OR_NOT_READY'
    result['success'] = result['configured'] and result['managedSshRunning'] and result['backendReady'] and result['aiReady']
    return result


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'plan', 'trust-host', 'start', 'status', 'check', 'stop'])
    parser.add_argument('--runtime-dir', required=True)
    args = parser.parse_args()
    try:
        if args.action == 'prepare':
            result = prepare(args.runtime_dir)
        elif args.action == 'trust-host':
            result = trust_host(args.runtime_dir)
        elif args.action == 'start':
            result = start(args.runtime_dir)
        elif args.action == 'stop':
            result = stop(args.runtime_dir)
        elif args.action == 'check':
            result = check(args.runtime_dir)
        else:
            runtime, _, missing = configuration(args.runtime_dir, complete=False)
            _, owned = owned_record(runtime)
            result = {'configured': not missing, 'missingFields': missing, 'managedSshRunning': owned,
                      'localAiPortListening': listening(LOCAL_AI), 'backendPortListening': listening(CALLBACK),
                      'aiReadinessVerified': False, 'keyTransferred': False}
        print(json.dumps(result))
        return 1 if result.get('success') is False else 0
    except (ConnectionError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(json.dumps({'success': False, 'error': str(error) if isinstance(error, ConnectionError) else 'LOCAL_CONNECTION_OPERATION_FAILED',
                          'detailsWithheld': True, 'keyTransferred': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
