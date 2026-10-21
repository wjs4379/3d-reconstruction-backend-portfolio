#!/usr/bin/env python3
"""Run a production bootJar against an isolated, real OAuth integration DB.

This helper never creates a database, starts a demo, performs OAuth login, or
changes a frontend. The operator separately provisions the dedicated database
and configures Kakao. All environment values are literal, never shell input.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

DEFAULT_RUNTIME = Path.home() / 'Documents/Codex/.runtime/backend-real-integration-20261016/runtime'
ENV_NAME = 'real-integration.env'
RECORD_NAME = 'backend.process.json'
DB_URL = 'jdbc:mysql://127.0.0.1:3327/gp_estate_real_integration?sslMode=REQUIRED&serverTimezone=Asia/Seoul'
CALLBACK = 'http://127.0.0.1:5173/oauth/callback/kakao'
REAL_REGISTRATION_STRATEGIES = frozenset({'vggt', 'hybrid_consensus', 'hybrid_mastr', 'da3_footprint'})
KAKAO_REQUIRED = ('KAKAO_APP_ID', 'KAKAO_REST_API_KEY', 'KAKAO_CLIENT_SECRET')
FIXED = {
    'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': '8083',
    'DB_URL': DB_URL, 'DB_USERNAME': 'gp_integration', 'JWT_ISSUER': 'gp-real-integration',
    'MEDIA_MODE': 'local', 'APP_PUBLIC_BASE_URL': 'http://127.0.0.1:8083',
    'APP_BACKEND_BASE_URL': 'http://127.0.0.1:8083',
    'APP_WEBHOOK_URL': 'http://127.0.0.1:8083/api/internal/ai/webhook',
    'CORS_ALLOWED_ORIGINS': 'http://127.0.0.1:5173,http://localhost:5173',
    'AI_SERVER_BASE_URL': 'http://127.0.0.1:18001',
    'KAKAO_REDIRECT_URI': CALLBACK, 'OUTBOX_ENABLED': 'true',
}
KEYS = set(FIXED) | set(KAKAO_REQUIRED) | {
    'DB_PASSWORD', 'JWT_SECRET', 'AI_SERVICE_KEY', 'MEDIA_LOCAL_DIRECTORY',
    'AI_PIPELINE_ENABLED', 'OUTBOX_WORKER_ENABLED', 'KAKAO_LOCAL_REST_API_KEY', 'GOOGLE_CLIENT_ID',
}


class IntegrationError(Exception):
    """A fixed error code, without raw environment, HTTP, or subprocess output."""


def clean_path(value):
    path = Path(value).expanduser()
    if not path.is_absolute() or '..' in path.parts or any(c in str(path) for c in '\r\n\x00'):
        raise IntegrationError('ABSOLUTE_NORMAL_PATH_REQUIRED')
    for component in [path] + list(path.parents):
        if component.is_symlink():
            raise IntegrationError('SYMLINK_NOT_ALLOWED')
    return path


def private_path(value, directory=False):
    path = clean_path(value)
    info = path.stat()
    required = 0o700 if directory else 0o600
    right_type = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    if not right_type or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != required:
        raise IntegrationError('PRIVATE_OWNER_PERMISSIONS_REQUIRED')
    if not directory and info.st_nlink != 1:
        raise IntegrationError('HARD_LINK_NOT_ALLOWED')
    return path


def runtime_path(value, create=False):
    path = clean_path(value)
    if create:
        path.mkdir(parents=True, mode=0o700, exist_ok=True)
    return private_path(path, directory=True)


def write_private(path, contents, exclusive=False):
    path = clean_path(path)
    private_path(path.parent, directory=True)
    if path.exists():
        private_path(path)
    if exclusive:
        fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as out:
            out.write(contents)
            out.flush()
            os.fsync(out.fileno())
        return
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as out:
            out.write(contents)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def read_private(path, maximum=65536):
    path = private_path(path)
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as source:
        info = os.fstat(source.fileno())
        if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
            raise IntegrationError('PRIVATE_OWNER_PERMISSIONS_REQUIRED')
        contents = source.read(maximum + 1)
    if len(contents) > maximum:
        raise IntegrationError('PRIVATE_FILE_TOO_LARGE')
    return contents.decode('utf-8')


def prepare(runtime):
    runtime = runtime_path(runtime, create=True)
    env_path = runtime / ENV_NAME
    if env_path.exists() or env_path.is_symlink():
        raise IntegrationError('ENV_ALREADY_EXISTS_NO_OVERWRITE')
    for name in ('media', 'tmp'):
        child = clean_path(runtime / name)
        child.mkdir(mode=0o700, exist_ok=True)
        private_path(child, directory=True)
    values = dict(FIXED)
    values.update({
        'DB_PASSWORD': secrets.token_hex(32), 'JWT_SECRET': secrets.token_hex(48),
        'AI_SERVICE_KEY': secrets.token_hex(48),
        'MEDIA_LOCAL_DIRECTORY': str(runtime / 'media'),
        'AI_PIPELINE_ENABLED': 'false', 'OUTBOX_WORKER_ENABLED': 'false',
        'KAKAO_APP_ID': '', 'KAKAO_REST_API_KEY': '', 'KAKAO_CLIENT_SECRET': '',
        'KAKAO_LOCAL_REST_API_KEY': '', 'GOOGLE_CLIENT_ID': '',
    })
    contents = '# Literal KEY=VALUE; do not source. Fill Kakao values privately.\n'
    contents += '\n'.join(key + '=' + values[key] for key in sorted(values)) + '\n'
    write_private(env_path, contents, exclusive=True)
    return {'prepared': True, 'envFile': str(env_path), 'missingKeys': list(KAKAO_REQUIRED),
            'databaseCreated': False, 'networkRequests': 0, 'aiEnabled': False, 'workerEnabled': False}


def configuration(runtime):
    runtime = runtime_path(runtime)
    values = {}
    for line in read_private(runtime / ENV_NAME).splitlines():
        if not line or line.lstrip().startswith('#'):
            continue
        if '=' not in line or '\x00' in line:
            raise IntegrationError('INVALID_LITERAL_ENV_LINE')
        key, value = line.split('=', 1)
        if key not in KEYS or key in values or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise IntegrationError('UNKNOWN_DUPLICATE_OR_INVALID_ENV_KEY')
        values[key] = value
    if set(values) != KEYS:
        raise IntegrationError('ENV_KEY_SET_MISMATCH')
    for key, expected in FIXED.items():
        if values[key] != expected:
            raise IntegrationError('ISOLATED_SETTING_MISMATCH_' + key)
    if values['MEDIA_LOCAL_DIRECTORY'] != str(runtime / 'media'):
        raise IntegrationError('ISOLATED_MEDIA_DIRECTORY_REQUIRED')
    for name in ('media', 'tmp'):
        private_path(runtime / name, directory=True)
    for key in ('DB_PASSWORD', 'JWT_SECRET', 'AI_SERVICE_KEY'):
        if not re.fullmatch(r'[A-Za-z0-9_+/=.-]{32,256}', values[key]):
            raise IntegrationError('INVALID_SECRET_FORMAT_' + key)
    if len({values[k] for k in ('DB_PASSWORD', 'JWT_SECRET', 'AI_SERVICE_KEY')}) != 3:
        raise IntegrationError('SEPARATE_SECRETS_REQUIRED')
    for key in ('AI_PIPELINE_ENABLED', 'OUTBOX_WORKER_ENABLED'):
        if values[key] not in ('true', 'false'):
            raise IntegrationError('EXPLICIT_BOOLEAN_REQUIRED_' + key)
    if values['OUTBOX_WORKER_ENABLED'] == 'true' and values['AI_PIPELINE_ENABLED'] != 'true':
        raise IntegrationError('WORKER_REQUIRES_AI_ENABLED')
    for key in KAKAO_REQUIRED:
        value = values[key]
        if not value:
            continue
        valid = re.fullmatch(r'[1-9][0-9]{0,19}', value) if key == 'KAKAO_APP_ID' else re.fullmatch(r'[A-Za-z0-9_.-]{16,256}', value)
        if not valid or any(marker in value.lower() for marker in ('placeholder', 'replace_me', 'your_', 'change_me')):
            raise IntegrationError('INVALID_OAUTH_FORMAT_' + key)
    for key in ('KAKAO_LOCAL_REST_API_KEY', 'GOOGLE_CLIENT_ID'):
        if values[key] and not re.fullmatch(r'[A-Za-z0-9_.-]{8,256}', values[key]):
            raise IntegrationError('INVALID_OPTIONAL_OAUTH_FORMAT_' + key)
    missing = [key for key in KAKAO_REQUIRED if not values[key]]
    return runtime, values, missing


def validate_jar(value):
    path = clean_path(value)
    info = path.stat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or
            info.st_mode & 0o022 or info.st_nlink != 1 or path.suffix != '.jar' or info.st_size > 512 * 1024 * 1024):
        raise IntegrationError('OWNED_PRODUCTION_JAR_REQUIRED')
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(names) > 50000 or len(set(names)) != len(names) or sum(e.file_size for e in entries) > 1024 ** 3:
                raise IntegrationError('INVALID_PRODUCTION_JAR_ARCHIVE')
            for entry in entries:
                name = entry.filename
                base = Path(name).name
                klass = base.split('$', 1)[0].removesuffix('.class')
                if (stat.S_ISLNK(entry.external_attr >> 16) or '..' in Path(name).parts or name.startswith('/') or
                        '/smoke/' in name or 'EstateSmokeServer' in name or 'EstateDemoServer' in name or
                        (name.startswith('BOOT-INF/classes/') and name.endswith('.class') and re.search(r'(Test|Tests|TestKt)$', klass)) or
                        re.search(r'(?:^|/)application-test\.(?:yml|yaml|properties)$', name) or
                        (name.startswith('BOOT-INF/lib/') and re.match(r'(junit|mockito|mockk|spring-boot[^/]*-test|spring-security-test|h2-)', base))):
                    raise IntegrationError('TEST_OR_FIXTURE_CLASSES_FORBIDDEN')
            manifest_info = archive.getinfo('META-INF/MANIFEST.MF')
            if manifest_info.file_size > 16384:
                raise IntegrationError('INVALID_BOOT_JAR_MANIFEST')
            raw = archive.read(manifest_info).decode('utf-8').replace('\r\n', '\n').replace('\n ', '')
            manifest = dict(line.split(': ', 1) for line in raw.splitlines() if ': ' in line)
            if (manifest.get('Main-Class') != 'org.springframework.boot.loader.launch.JarLauncher' or
                    manifest.get('Start-Class') != 'com.hanyang.backend.BackendApplication' or
                    'BOOT-INF/classes/com/hanyang/backend/BackendApplication.class' not in names):
                raise IntegrationError('PRODUCTION_BOOT_JAR_REQUIRED')
    except IntegrationError:
        raise
    except (KeyError, ValueError, OSError, UnicodeError, zipfile.BadZipFile):
        raise IntegrationError('INVALID_PRODUCTION_JAR') from None
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return path, digest.hexdigest()


def check(runtime, jar=None):
    runtime, values, missing = configuration(runtime)
    result = {'ok': not missing, 'missingKeys': missing, 'envFile': str(runtime / ENV_NAME),
              'aiEnabled': values['AI_PIPELINE_ENABLED'] == 'true',
              'workerEnabled': values['OUTBOX_WORKER_ENABLED'] == 'true',
              'networkChecked': False, 'oauthLoginVerified': False}
    if jar is not None:
        _, result['jarSha256'] = validate_jar(jar)
        result['productionBootJarChecked'] = True
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get_json(url, key=None):
    headers = {'Accept': 'application/json'}
    if key is not None:
        headers['X-AI-Service-Key'] = key
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, method='GET', headers=headers), timeout=3) as response:
            raw = response.read(16385)
            if response.status != 200 or len(raw) > 16384:
                raise IntegrationError('READINESS_RESPONSE_REJECTED')
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise IntegrationError('READINESS_RESPONSE_REJECTED')
            return data
    except Exception:
        raise IntegrationError('READINESS_UNREACHABLE_OR_INVALID') from None


def load_connection_helper():
    path = Path(__file__).with_name('local_ai_connection.py')
    spec = importlib.util.spec_from_file_location('real_integration_ai_connection', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_owned_tunnel(runtime):
    """Validate the configured SSH process and listener before exposing the AI key."""
    try:
        helper = load_connection_helper()
        _, config, _ = helper.configuration(runtime)
        record, owned = helper.owned_record(runtime)
        if not owned or not isinstance(record, dict) or type(record.get('pid')) is not int or record['pid'] < 2:
            raise IntegrationError('VERIFIED_MANAGED_AI_TUNNEL_REQUIRED')
        command = helper.ssh_argv(config)
        if record.get('argvSha256') != hashlib.sha256(json.dumps(command).encode()).hexdigest():
            raise IntegrationError('VERIFIED_MANAGED_AI_TUNNEL_REQUIRED')
        if helper.fingerprints(config['knownHostsFile']) != [config['expectedHostFingerprint']]:
            raise IntegrationError('VERIFIED_MANAGED_AI_TUNNEL_REQUIRED')
        uid, _, arguments = process_snapshot(record['pid'])
        if uid != os.geteuid() or os.getpgid(record['pid']) != record['pid'] or arguments != ' '.join(command):
            raise IntegrationError('VERIFIED_MANAGED_AI_TUNNEL_REQUIRED')
        require_listener_owned(record['pid'], port=18001)
    except Exception:
        # Config/SSH errors can carry a hostname or subprocess details. No key
        # has been sent by this guard, and none of those details are reported.
        raise IntegrationError('VERIFIED_MANAGED_AI_TUNNEL_REQUIRED') from None


def authenticated_ai_get(runtime, values, path):
    require_owned_tunnel(runtime)
    return get_json(values['AI_SERVER_BASE_URL'] + path, values['AI_SERVICE_KEY'])


def require_ai_ready(runtime, values):
    if values['AI_PIPELINE_ENABLED'] != 'true':
        return
    data = authenticated_ai_get(runtime, values, '/api/health/ready')
    checks = data.get('checks')
    if data.get('status') != 'ready' or not isinstance(checks, dict) or not all(checks.get(k) is True for k in ('worker', 'model', 'database')):
        raise IntegrationError('AI_NOT_STRICTLY_READY')
    if values['OUTBOX_WORKER_ENABLED'] != 'true':
        return
    gpu = authenticated_ai_get(runtime, values, '/api/health/gpu')
    count, devices = gpu.get('deviceCount'), gpu.get('devices')
    if not (gpu.get('cudaAvailable') is True and type(count) is int and count > 0 and
            isinstance(devices, list) and len(devices) >= count and all(isinstance(d, dict) for d in devices[:count])):
        raise IntegrationError('REAL_GPU_REQUIRED_BEFORE_WORKER_ENABLE')
    checkpoints = authenticated_ai_get(runtime, values, '/api/health/checkpoints')
    registration = checkpoints.get('registration')
    strategy = registration.get('strategy') if isinstance(registration, dict) else None
    if (not isinstance(registration, dict) or registration.get('modelLoaded') is not True or
            not isinstance(strategy, str) or strategy not in REAL_REGISTRATION_STRATEGIES):
        raise IntegrationError('REAL_CHECKPOINT_REQUIRED_BEFORE_WORKER_ENABLE')


def child_environment(runtime, values):
    # Do not inherit Spring/Java overrides, classpaths, credentials, or proxy settings.
    result = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': str(Path.home()),
              'TMPDIR': str(runtime / 'tmp'), 'TZ': 'Asia/Seoul', 'LANG': 'en_US.UTF-8'}
    result.update(values)
    return result


def process_snapshot(pid):
    try:
        result = subprocess.run(['ps', '-ww', '-p', str(pid), '-o', 'uid=', '-o', 'lstart=', '-o', 'args='],
                                capture_output=True, text=True, timeout=5, env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'})
    except (OSError, subprocess.SubprocessError):
        raise IntegrationError('PROCESS_IDENTITY_UNAVAILABLE') from None
    match = re.fullmatch(r'\s*(\d+)\s+(\S+\s+\S+\s+\d+\s+\d{2}:\d{2}:\d{2}\s+\d{4})\s+(.+?)\s*', result.stdout)
    if result.returncode or not match:
        raise IntegrationError('PROCESS_IDENTITY_UNAVAILABLE')
    return int(match.group(1)), ' '.join(match.group(2).split()), match.group(3)


def process_exists(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def read_record(runtime):
    path = runtime / RECORD_NAME
    if not path.exists() and not path.is_symlink():
        return None
    try:
        record = json.loads(read_private(path))
        command = record.get('command')
        if (not isinstance(record, dict) or record.get('version') != 1 or
                type(record.get('pid')) is not int or record['pid'] <= 1 or
                record.get('uid') != os.geteuid() or record.get('runtime') != str(runtime) or
                not isinstance(record.get('birthTime'), str) or not record['birthTime'] or
                not re.fullmatch(r'[0-9a-f]{64}', record.get('jarSha256', '')) or
                not isinstance(command, list) or len(command) != 3 or command[1] != '-jar' or
                not all(isinstance(x, str) and x and not any(c in x for c in '\r\n\x00') for x in command) or
                not Path(command[0]).is_absolute() or Path(command[0]).name != 'java' or
                not Path(command[2]).is_absolute() or Path(command[2]).suffix != '.jar'):
            raise IntegrationError('INVALID_PROCESS_RECORD')
        return record
    except (ValueError, TypeError, AttributeError):
        raise IntegrationError('INVALID_PROCESS_RECORD') from None


def require_owned(record):
    pid = record['pid']
    uid, birth, args = process_snapshot(pid)
    if uid != os.geteuid() or os.getpgid(pid) != pid or birth != record['birthTime'] or args != ' '.join(record['command']):
        raise IntegrationError('LIVE_PROCESS_IDENTITY_MISMATCH_NO_SIGNAL_SENT')


def status(runtime):
    runtime = runtime_path(runtime)
    record = read_record(runtime)
    if not record or not process_exists(record['pid']):
        return {'state': 'stopped', 'recordPresent': bool(record)}
    require_owned(record)
    return {'state': 'running', 'pid': record['pid'], 'jarSha256': record['jarSha256'], 'port': 8083}


def stop(runtime, timeout=20):
    runtime = runtime_path(runtime)
    record = read_record(runtime)
    if not record:
        return {'state': 'stopped', 'signalSent': False}
    signalled = False
    if process_exists(record['pid']):
        require_owned(record)
        os.kill(record['pid'], signal.SIGTERM)
        signalled = True
        deadline = time.monotonic() + timeout
        while process_exists(record['pid']) and time.monotonic() < deadline:
            time.sleep(0.2)
        if process_exists(record['pid']):
            raise IntegrationError('OWNED_PROCESS_DID_NOT_STOP_NO_FORCE_KILL')
    private_path(runtime / RECORD_NAME).unlink()
    return {'state': 'stopped', 'signalSent': signalled}


def require_free_port():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(('127.0.0.1', 8083))
    except OSError:
        raise IntegrationError('BACKEND_PORT_8083_ALREADY_IN_USE') from None


def require_listener_owned(pid, port=8083):
    # Port reservation is released before exec. Verify readiness came from this
    # Java process, rather than a different listener that won the bind race.
    executable = '/usr/sbin/lsof' if Path('/usr/sbin/lsof').is_file() else shutil.which('lsof')
    if not executable:
        raise IntegrationError('LSOF_REQUIRED_FOR_LISTENER_IDENTITY')
    result = subprocess.run([executable, '-nP', '-a', '-iTCP:' + str(port), '-sTCP:LISTEN', '-Fp'],
                            capture_output=True, text=True, timeout=5,
                            env={'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'LC_ALL': 'C'})
    listeners = {int(line[1:]) for line in result.stdout.splitlines() if re.fullmatch(r'p[0-9]+', line)}
    if result.returncode or listeners != {pid}:
        raise IntegrationError('READINESS_LISTENER_IDENTITY_MISMATCH')


def java_executable(value=None):
    candidate = value or (str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'))
    if not candidate:
        raise IntegrationError('JAVA_21_EXECUTABLE_REQUIRED')
    # System Java launchers can be symlinks; resolve the executable, never the runtime/env/jar.
    path = Path(candidate).expanduser().resolve()
    if path.name != 'java' or not path.is_file() or not os.access(path, os.X_OK):
        raise IntegrationError('JAVA_EXECUTABLE_REQUIRED')
    try:
        result = subprocess.run([str(path), '-version'], capture_output=True, text=True, timeout=10,
                                env={'PATH': '/usr/bin:/bin', 'HOME': str(Path.home())})
        if result.returncode or not re.search(r'version "21(?:[.\-+"\s])', result.stderr + result.stdout):
            raise IntegrationError('JAVA_21_EXECUTABLE_REQUIRED')
    except (OSError, subprocess.SubprocessError):
        raise IntegrationError('JAVA_21_EXECUTABLE_REQUIRED') from None
    return path


def start(runtime, jar, java=None, startup_timeout=180):
    runtime, values, missing = configuration(runtime)
    if missing:
        raise IntegrationError('MISSING_OAUTH_KEYS_' + '_'.join(missing))
    jar, digest = validate_jar(jar)
    record = read_record(runtime)
    if record and process_exists(record['pid']):
        require_owned(record)
        raise IntegrationError('ALREADY_RUNNING_STOP_BEFORE_CHANGING_ENV')
    require_free_port()
    require_ai_ready(runtime, values)
    java = java_executable(java)
    command = [str(java), '-jar', str(jar)]
    log = clean_path(runtime / 'backend.log')
    if log.exists():
        private_path(log)
    fd = os.open(str(log), os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'ab') as stream:
        proc = subprocess.Popen(command, cwd=str(runtime), env=child_environment(runtime, values),
                                stdin=subprocess.DEVNULL, stdout=stream, stderr=stream,
                                start_new_session=True, umask=0o077)
    owned = None
    try:
        uid, birth, args = process_snapshot(proc.pid)
        owned = {'version': 1, 'pid': proc.pid, 'uid': uid, 'birthTime': birth,
                 'command': command, 'jarSha256': digest, 'runtime': str(runtime)}
        require_owned(owned)
        write_private(runtime / RECORD_NAME, json.dumps(owned, indent=2) + '\n')
        deadline = time.monotonic() + startup_timeout
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise IntegrationError('BACKEND_EXITED_PRIVATE_LOG_AVAILABLE')
            require_owned(owned)
            try:
                ready = get_json('http://127.0.0.1:8083/actuator/health/readiness')
            except IntegrationError:
                pass
            else:
                if ready.get('status') == 'UP':
                    require_listener_owned(proc.pid)
                    return {'state': 'running', 'pid': proc.pid, 'port': 8083, 'jarSha256': digest,
                            'readiness': 'UP', 'aiEnabled': values['AI_PIPELINE_ENABLED'] == 'true',
                            'workerEnabled': values['OUTBOX_WORKER_ENABLED'] == 'true', 'oauthLoginVerified': False}
            time.sleep(0.5)
        raise IntegrationError('BACKEND_READINESS_TIMEOUT_PRIVATE_LOG_AVAILABLE')
    except Exception:
        if owned is not None and process_exists(proc.pid):
            # Never terminate a process after an identity mismatch, even during cleanup.
            require_owned(owned)
            os.kill(proc.pid, signal.SIGTERM)
        elif owned is None and proc.poll() is None:
            # Popen still owns its unreaped child; no PID record from elsewhere is used.
            proc.terminate()
        raise


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'check', 'start', 'status', 'stop'])
    parser.add_argument('--runtime', default=str(DEFAULT_RUNTIME))
    parser.add_argument('--jar')
    parser.add_argument('--java')
    parser.add_argument('--startup-timeout', type=int, default=180)
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.startup_timeout <= 300:
            raise IntegrationError('STARTUP_TIMEOUT_MUST_BE_1_TO_300_SECONDS')
        if args.action == 'prepare':
            result = prepare(args.runtime)
        elif args.action == 'check':
            result = check(args.runtime, args.jar)
        elif args.action == 'start':
            if not args.jar:
                raise IntegrationError('EXPLICIT_PRODUCTION_JAR_REQUIRED')
            result = start(args.runtime, args.jar, args.java, args.startup_timeout)
        elif args.action == 'status':
            result = status(args.runtime)
        else:
            result = stop(args.runtime)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0 if result.get('ok', True) else 2
    except IntegrationError as error:
        print(json.dumps({'ok': False, 'error': str(error)}, sort_keys=True))
        return 2
    except (OSError, ValueError, UnicodeError, subprocess.SubprocessError):
        # Raw exception messages may contain response bodies, private paths or values.
        print(json.dumps({'ok': False, 'error': 'LOCAL_OPERATION_FAILED_PRIVATE_DETAILS_SUPPRESSED'}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
