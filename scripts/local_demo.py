#!/usr/bin/env python3
"""Owner-only, loopback MySQL demo. Never a production or GPU deployment entry point.

Starts the installed native MySQL in a separate data directory; no system DB, sudo,
DDL update, or reset. Credentials stay in a 0600 JSON file. Existing data is reused.
"""
import argparse
import hashlib
import json
import os
import secrets
import re
import stat
import tempfile
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

# Also support importlib callers that load this private helper outside the repo cwd.
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
from backup_database_fingerprint import database_fingerprint, literal_grant_identifier

REPO = Path(__file__).resolve().parents[1]
BASE = REPO.parent
DEFAULT_RUNTIME = Path.home() / "Documents/Codex/.runtime/estate-v4"
CONFIG_NAME = "local-secrets.json"


def private_text(path, value):
    path = Path(path)
    if path.is_symlink():
        raise RuntimeError("Refusing a symbolic link for private runtime material")
    if path.exists() and (not path.is_file() or path.stat().st_uid != os.geteuid()):
        raise RuntimeError("Runtime file must belong to the current local owner")
    fd, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as out:
            out.write(value)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def private_json(path, value):
    private_text(path, json.dumps(value, ensure_ascii=False, indent=2))


def configuration(runtime):
    runtime = runtime.expanduser().resolve()
    runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
    if runtime.stat().st_uid != os.geteuid():
        raise RuntimeError("Runtime directory must belong to the current local owner")
    os.chmod(runtime, 0o700)
    path = runtime / CONFIG_NAME
    if path.is_symlink():
        raise RuntimeError("Secrets file must not be a symbolic link")
    if not path.exists():
        private_json(path, {"version": 1, "mysqlPort": 3327, "backendPort": 8082,
            "aiPort": 8001, "database": "gp_estate_demo", "dbUsername": "gp_demo",
            "dbPassword": secrets.token_hex(32), "rootPassword": secrets.token_hex(32),
            "testPassword": secrets.token_hex(32), "jwtSecret": secrets.token_hex(48),
            "aiServiceKey": secrets.token_hex(48)})
    if not path.is_file() or path.stat().st_uid != os.geteuid() or path.stat().st_mode & 0o077:
        raise RuntimeError("Secrets file must be an owner-only regular file")
    if path.stat().st_size > 16_384:
        raise RuntimeError("Runtime configuration exceeds the allowed size")
    config = json.loads(path.read_text())
    if config.get("version") != 1 or config.get("database") != "gp_estate_demo" or config.get("dbUsername") != "gp_demo":
        raise RuntimeError("This helper only manages the isolated local demo DB")
    for key in ("mysqlPort", "backendPort", "aiPort"):
        if type(config.get(key)) is not int or not 1024 <= config[key] <= 65535:
            raise RuntimeError("An unprivileged integer port is required")
    if len({config[key] for key in ("mysqlPort", "backendPort", "aiPort")}) != 3:
        raise RuntimeError("MySQL, backend and AI must use distinct ports")
    for key in ("rootPassword", "dbPassword", "testPassword", "jwtSecret", "aiServiceKey"):
        if not isinstance(config.get(key), str) or not re.fullmatch(r"[0-9a-f]{64,}", config[key]):
            raise RuntimeError("Runtime secrets must be random hexadecimal values of at least 32 bytes")
    return runtime, config


def executable(path, name):
    result = Path(path).expanduser() / "bin" / name
    if not result.is_file():
        raise RuntimeError("Installed native MySQL binaries are required; specify --mysql-home")
    return str(result)


def listening(port):
    with socket.socket() as probe:
        probe.settimeout(0.3)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def process_snapshot(pid):
    # ps start time survives exec(gradlew -> Java), unlike its executable name.
    result = subprocess.check_output(["ps", "-ww", "-p", str(pid), "-o", "lstart=", "-o", "args="], text=True).strip()
    match = re.match(r"^(\S+\s+\S+\s+\d+\s+\d{2}:\d{2}:\d{2}\s+\d{4})\s+(.+)$", result)
    if not match:
        raise RuntimeError("Cannot identify the managed process birth time and arguments")
    return " ".join(match.group(1).split()), match.group(2)


def arguments_match(command, arguments):
    if not command or not all(isinstance(part, str) and part for part in command):
        return False
    def contains(part):
        return re.search(r"(?:^|\s)" + re.escape(part) + r"(?:$|\s)", arguments) is not None
    if command[0].endswith("/gradlew"):
        repo = Path(command[0]).parent
        required = [str(repo / "gradle/wrapper/gradle-wrapper.jar")] + command[1:]
        wrapper_launcher = contains("org.gradle.wrapper.GradleWrapperMain") or contains("-jar")
        return wrapper_launcher and all(contains(part) for part in required)
    return all(contains(part) for part in command)


def record_process(runtime, name, command, cwd, env=None):
    record = runtime / (name + ".process.json")
    if record.is_symlink():
        raise RuntimeError("Managed process records must not be symbolic links")
    if record.exists():
        old = json.loads(record.read_text())
        if process_owned(old):
            birth, _ = process_snapshot(old["pid"])
            old["birthTime"] = birth
            private_json(record, old)
            return old["pid"]
        record.unlink()
    log_path = runtime / (name + ".log")
    if log_path.is_symlink():
        raise RuntimeError("Runtime log must not be a symbolic link")
    fd = os.open(log_path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "ab") as log:
        proc = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                stdout=log, stderr=log, start_new_session=True)
    try:
        birth, _ = process_snapshot(proc.pid)
        private_json(record, {"pid": proc.pid, "birthTime": birth, "command": command, "cwd": str(cwd)})
    except Exception:
        # A process that exits before its record is written is a startup failure.
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
        raise RuntimeError(name + " exited before it could be safely registered")
    return proc.pid


def process_owned(record):
    try:
        pid = record["pid"]
        if type(pid) is not int or pid <= 1 or os.getpgid(pid) != pid:
            return False
        os.kill(pid, 0)
        birth, arguments = process_snapshot(pid)
        if record.get("birthTime") is not None and record["birthTime"] != birth:
            return False
        # Legacy records still require the full runtime/task identity. No empty all() acceptance.
        return arguments_match(record["command"], arguments)
    except (OSError, RuntimeError, subprocess.SubprocessError, KeyError, TypeError):
        return False


def require_port_available_or_owned(runtime, name, port):
    if not listening(port):
        return
    record = runtime / (name + ".process.json")
    if record.is_symlink() or not record.exists() or not process_owned(json.loads(record.read_text())):
        raise RuntimeError(name + " port is occupied by an unmanaged process")


def process_group_alive(pgid):
    try:
        rows = subprocess.check_output(["ps", "-A", "-o", "pgid=", "-o", "state="], text=True)
        return any(parts[0] == str(pgid) and not parts[1].startswith("Z")
                   for row in rows.splitlines() if len(parts := row.split()) >= 2)
    except subprocess.SubprocessError:
        return True  # Never label a possibly live writer stopped after an inspection error.


def stop_process(runtime, name):
    record = runtime / (name + ".process.json")
    if not record.exists():
        return
    if record.is_symlink():
        raise RuntimeError("Managed process records must not be symbolic links")
    data = json.loads(record.read_text())
    if not process_owned(data):
        record.unlink()
        return
    os.killpg(data["pid"], signal.SIGTERM)
    for _ in range(100):
        # Gradle's wrapper can exit before its Spring/Gradle child finishes shutdown.
        if not process_group_alive(data["pid"]):
            record.unlink(missing_ok=True)
            return
        time.sleep(0.2)
    raise RuntimeError(name + " did not stop gracefully; no forced kill or data deletion was performed")


def wait_http(url, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("Service is not ready; check private runtime logs: " + url)


def mysql_command(runtime, config, mysql_home, program="mysql", root=False, test=False):
    account = "root" if root else "test" if test else "app"
    settings = runtime / (account + "-client.cnf")
    password = config["rootPassword"] if root else config["testPassword"] if test else config["dbPassword"]
    username = "root" if root else "gp_test" if test else config["dbUsername"]
    connection = ("protocol=socket\nsocket=" + str(runtime / "mysql.sock") if root else
                  "host=127.0.0.1\nprotocol=tcp\nport=" + str(config["mysqlPort"]))
    private_text(settings, "[client]\n" + connection + "\nuser=" + username + "\npassword=" + password + "\n")
    return [executable(mysql_home, program), "--defaults-file=" + str(settings)]


def mysql_query(runtime, config, mysql_home, query, root=False):
    result = subprocess.run(mysql_command(runtime, config, mysql_home, root=root) + ["--batch", "--skip-column-names"],
                            input=query, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("MySQL operation failed; credentials were not printed")
    return result.stdout.strip()


def mysql_row_digest(command, query):
    """Spool rows to a private anonymous file and hash chunks, without printing rows."""
    with tempfile.TemporaryFile() as rows:
        result = subprocess.run(command, input=query.encode(), stdout=rows, stderr=subprocess.PIPE, timeout=300)
        if result.returncode:
            raise RuntimeError("MySQL row fingerprint failed; private values were not printed")
        rows.seek(0)
        digest = hashlib.sha256()
        for chunk in iter(lambda: rows.read(1024 * 1024), b""):
            digest.update(chunk)
        return digest.hexdigest()


def verify_mysql_accounts(runtime, config, mysql_home):
    query = ("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('gp_estate_demo','estate_migration_test');")
    if mysql_query(runtime, config, mysql_home, query, root=True) != "2":
        raise RuntimeError("Existing isolated MySQL requires completed database provisioning")
    for test in (False, True):
        result = subprocess.run(mysql_command(runtime, config, mysql_home, test=test) + ["--batch", "--skip-column-names"],
            input="SELECT 1;", text=True, capture_output=True)
        if result.returncode or result.stdout.strip() != "1":
            raise RuntimeError("Existing isolated MySQL account provisioning is incomplete")


def secure_mysql_bootstrap(runtime, config, mysql_home):
    socket_path = runtime / "bootstrap.sock"
    command = [executable(mysql_home, "mysqld"), "--no-defaults", "--basedir=" + str(Path(mysql_home).resolve()),
        "--datadir=" + str(runtime / "mysql-data"), "--skip-networking", "--mysqlx=0",
        "--socket=" + str(socket_path), "--pid-file=" + str(runtime / "bootstrap.pid"),
        "--log-error=" + str(runtime / "mysql-bootstrap-error.log")]
    record_process(runtime, "mysql-bootstrap", command, runtime)
    no_password = [executable(mysql_home, "mysql"), "--no-defaults", "--protocol=socket",
        "--socket=" + str(socket_path), "--user=root", "--batch"]
    settings = runtime / "bootstrap-client.cnf"
    private_text(settings, "[client]\nprotocol=socket\nsocket=" + str(socket_path) +
                 "\nuser=root\npassword=" + config["rootPassword"] + "\n")
    secured = [executable(mysql_home, "mysql"), "--defaults-file=" + str(settings), "--batch"]
    try:
        connected = None
        for _ in range(80):
            for candidate in (no_password, secured):
                result = subprocess.run(candidate, input="SELECT 1;", text=True, capture_output=True)
                if result.returncode == 0:
                    connected = candidate
                    break
            if connected is not None:
                break
            time.sleep(0.25)
        if connected is None:
            raise RuntimeError("Socket-only bootstrap is unavailable; initialized data was preserved")
        setting = subprocess.run(connected, input="SELECT @@GLOBAL.partial_revokes;", text=True, capture_output=True)
        if setting.returncode or not setting.stdout.strip():
            raise RuntimeError("Cannot determine literal database grant semantics")
        partial = setting.stdout.strip().splitlines()[-1]
        demo_grant = literal_grant_identifier("gp_estate_demo", partial)
        test_grant = literal_grant_identifier("estate_migration_test", partial)
        # Idempotent after partial bootstrap: root may already have a password,
        # while later CREATE USER/GRANT statements were interrupted.
        sql = ("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + config["rootPassword"] + "';\n"
            "CREATE DATABASE IF NOT EXISTS gp_estate_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
            "CREATE DATABASE IF NOT EXISTS estate_migration_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
            "CREATE USER IF NOT EXISTS 'gp_demo'@'127.0.0.1' IDENTIFIED BY '" + config["dbPassword"] + "';\n"
            "ALTER USER 'gp_demo'@'127.0.0.1' IDENTIFIED BY '" + config["dbPassword"] + "';\n"
            "REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'gp_demo'@'127.0.0.1';\n"
            "GRANT ALL PRIVILEGES ON " + demo_grant + ".* TO 'gp_demo'@'127.0.0.1';\n"
            "CREATE USER IF NOT EXISTS 'gp_test'@'127.0.0.1' IDENTIFIED BY '" + config["testPassword"] + "';\n"
            "ALTER USER 'gp_test'@'127.0.0.1' IDENTIFIED BY '" + config["testPassword"] + "';\n"
            "REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'gp_test'@'127.0.0.1';\n"
            "GRANT ALL PRIVILEGES ON " + test_grant + ".* TO 'gp_test'@'127.0.0.1';\n")
        result = subprocess.run(connected, input=sql, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError("Socket-only MySQL bootstrap failed; retry safely with the same runtime")
    finally:
        stop_process(runtime, "mysql-bootstrap")
    private_json(runtime / "mysql-bootstrap-complete.json", {"version": 1, "database": config["database"]})


def start_mysql(runtime, config, mysql_home):
    datadir = runtime / "mysql-data"
    if datadir.is_symlink():
        raise RuntimeError("MySQL data directory must not be a symbolic link")
    if not (datadir / "mysql").is_dir():
        if datadir.exists() and any(datadir.iterdir()):
            raise RuntimeError("Nonempty uninitialized MySQL directory; refusing to overwrite")
        datadir.mkdir(exist_ok=True, mode=0o700)
        log_path = runtime / "mysql-initialize.log"
        if log_path.is_symlink():
            raise RuntimeError("Initialization log must not be a symbolic link")
        with open(log_path, "ab") as log:
            os.chmod(log_path, 0o600)
            result = subprocess.run([executable(mysql_home, "mysqld"), "--no-defaults", "--initialize-insecure",
                "--basedir=" + str(Path(mysql_home).resolve()), "--datadir=" + str(datadir),
                "--lower-case-table-names=2"], stdout=log, stderr=log)
        if result.returncode:
            raise RuntimeError("MySQL initialization failed; inspect owner-only mysql-initialize.log")
    marker = runtime / "mysql-bootstrap-complete.json"
    if marker.is_symlink():
        raise RuntimeError("MySQL bootstrap marker must not be a symbolic link")
    require_port_available_or_owned(runtime, "mysql", config["mysqlPort"])
    if not marker.exists():
        if listening(config["mysqlPort"]):
            # Adopt old successful helper runtimes only after authenticating all accounts.
            verify_mysql_accounts(runtime, config, mysql_home)
            private_json(marker, {"version": 1, "database": config["database"]})
        else:
            secure_mysql_bootstrap(runtime, config, mysql_home)
    command = [executable(mysql_home, "mysqld"), "--no-defaults", "--basedir=" + str(Path(mysql_home).resolve()),
        "--datadir=" + str(datadir), "--bind-address=127.0.0.1", "--port=" + str(config["mysqlPort"]),
        "--mysqlx=0", "--socket=" + str(runtime / "mysql.sock"), "--pid-file=" + str(runtime / "mysql.pid"),
        "--log-error=" + str(runtime / "mysql-error.log"), "--skip-name-resolve",
        "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci"]
    record_process(runtime, "mysql", command, runtime)
    for _ in range(100):
        try:
            if mysql_query(runtime, config, mysql_home, "SELECT 1;") == "1":
                print("MySQL ready: 127.0.0.1:" + str(config["mysqlPort"]) + " (isolated durable data)")
                return
        except RuntimeError:
            pass
        time.sleep(0.2)
    raise RuntimeError("Native MySQL is not ready; check the private MySQL log")


def backend_environment(args, runtime):
    env = os.environ.copy()
    java_home = args.java_home or env.get("JAVA_HOME")
    if not java_home:
        candidate = Path.home() / "Documents/Codex/.runtime/estate-v1/jdk-21.0.12.1+1/Contents/Home"
        if candidate.exists():
            java_home = str(candidate)
        else:
            try:
                java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
            except Exception:
                raise RuntimeError("Set JAVA_HOME to a JDK 21 installation")
    env.update({"JAVA_HOME": java_home, "ESTATE_DEMO_CONFIG": str(runtime / CONFIG_NAME)})
    return env


def start_services(args, runtime, config):
    start_mysql(runtime, config, args.mysql_home)
    if args.database_only:
        return
    env = backend_environment(args, runtime)
    require_port_available_or_owned(runtime, "backend", config["backendPort"])
    record_process(runtime, "backend", [str(REPO / "gradlew"), "--no-daemon", "estateDemo"], REPO, env)
    wait_http("http://127.0.0.1:" + str(config["backendPort"]) + "/actuator/health/readiness")
    python = args.ai_python or str(Path.home() / "Documents/Codex/.runtime/estate-v2/ai-tests/bin/python")
    if not Path(python).is_file():
        raise RuntimeError("Pass --ai-python with the configured CPU demo venv; no model download is performed")
    require_port_available_or_owned(runtime, "ai", config["aiPort"])
    record_process(runtime, "ai", [python, "-m", "scripts.start_local_demo", "--runtime", str(runtime),
        "--backend-origin", "http://127.0.0.1:" + str(config["backendPort"]), "--port", str(config["aiPort"])], BASE / "AI", env)
    wait_http("http://127.0.0.1:" + str(config["aiPort"]) + "/api/health", 60)
    if not args.no_frontend:
        require_port_available_or_owned(runtime, "frontend", args.frontend_port)
        node = args.node or str(Path.home() / ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node")
        if not Path(node).is_file():
            node = shutil.which("node")
        if not node:
            raise RuntimeError("Install Node or pass --node")
        frontend_env = env.copy()
        frontend_env.update({"VITE_LOCAL_DEMO_LOGIN": "true", "VITE_API_BASE_URL": "/api", "VITE_BASE_PATH": "/",
            "VITE_API_PROXY_TARGET": "http://127.0.0.1:" + str(config["backendPort"])})
        record_process(runtime, "frontend", [node, str(BASE / "FrontEnd/node_modules/vite/bin/vite.js"),
            "--host", "127.0.0.1", "--port", str(args.frontend_port), "--strictPort"], BASE / "FrontEnd", frontend_env)
        wait_http("http://127.0.0.1:" + str(args.frontend_port) + "/login", 30)
        print("Login: http://127.0.0.1:" + str(args.frontend_port) + "/login")
    print("Data survives backend/MySQL restarts. Local fixture login and CPU mock AI only; not cloud deployment.")


def create_backup_files(args, runtime, config, destination):
    destination.mkdir(parents=True, mode=0o700)
    os.chmod(destination, 0o700)
    dump = destination / "backend.sql"
    with dump.open("wb") as out:
        os.chmod(dump, 0o600)
        result = subprocess.run(mysql_command(runtime, config, args.mysql_home, "mysqldump") +
            ["--single-transaction", "--routines", "--triggers", "--events", "--no-tablespaces", config["database"]],
            stdout=out, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Backup SQL dump failed; incomplete backup preserved for inspection")
    # backup() has paused every managed writer for this same dump/snapshot window.
    fingerprint = database_fingerprint(lambda sql: mysql_query(runtime, config, args.mysql_home, sql), config["database"],
        lambda sql: mysql_row_digest(mysql_command(runtime, config, args.mysql_home) +
            ["--batch", "--skip-column-names", "--default-character-set=utf8mb4"], sql))
    media = runtime / "media"
    if media.is_symlink() or (media.exists() and any(path.is_symlink() for path in media.rglob("*"))):
        raise RuntimeError("Media backup refuses symbolic links")
    with tarfile.open(destination / "media.tar.gz", "w:gz") as archive:
        if media.exists():
            archive.add(media, arcname="media")
    ai_db = runtime / "ai-demo.db"
    if ai_db.is_symlink():
        raise RuntimeError("AI backup refuses a symbolic link")
    if ai_db.exists():
        with sqlite3.connect(ai_db.as_uri() + "?mode=ro", uri=True) as source, sqlite3.connect(destination / "ai-demo.db") as target:
            source.backup(target)
    # Secret sidecar stays private; it is never shareable test evidence.
    private_text(destination / CONFIG_NAME, (runtime / CONFIG_NAME).read_text())
    items = {}
    for path in destination.iterdir():
        if path.is_file():
            os.chmod(path, 0o600)
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            items[path.name] = {"bytes": path.stat().st_size, "sha256": digest.hexdigest()}
    private_json(destination / "backup-manifest.json", {"version": 1, "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "database": config["database"], "files": items, "databaseFingerprint": fingerprint,
        "note": "Managed backend and AI writers paused for logical snapshots. Keep the whole backup private."})


def backup(args, runtime, config):
    if not args.output:
        parent = runtime / "backups"
        if parent.is_symlink() or (parent.exists() and (not parent.is_dir() or parent.stat().st_uid != os.geteuid())):
            raise RuntimeError("Private backup parent must be an owned directory without symlinks")
        parent.mkdir(mode=0o700, exist_ok=True)
        os.chmod(parent, 0o700)
    raw_destination = Path(args.output).expanduser() if args.output else runtime / "backups" / time.strftime("%Y%m%d-%H%M%S")
    if raw_destination.is_symlink():
        raise RuntimeError("Backup destination must not be a symbolic link")
    destination = raw_destination.resolve()
    if destination.exists():
        raise RuntimeError("Backup destination must be new; no previous backup is overwritten")
    owned = []
    for name in ("frontend", "ai", "backend"):
        record = runtime / (name + ".process.json")
        if record.is_symlink():
            raise RuntimeError("Managed process records must not be symbolic links")
        data = json.loads(record.read_text()) if record.exists() else None
        active = data is not None and process_owned(data)
        if name in ("backend", "ai") and listening(config[name + "Port"]) and not active:
            raise RuntimeError("Cannot pause an unmanaged " + name + " writer for a consistent backup")
        if active:
            owned.append((name, data))
    stopped = []
    try:
        # AI drains callbacks while the backend still runs, then backend DB/media writes stop.
        for name, record in owned:
            stop_process(runtime, name)
            stopped.append((name, record))
        create_backup_files(args, runtime, config, destination)
    finally:
        errors = []
        for name, record in reversed(stopped):
            try:
                env = backend_environment(args, runtime)
                if name == "frontend":
                    env.update({"VITE_LOCAL_DEMO_LOGIN": "true", "VITE_API_BASE_URL": "/api", "VITE_BASE_PATH": "/",
                        "VITE_API_PROXY_TARGET": "http://127.0.0.1:" + str(config["backendPort"])})
                record_process(runtime, name, record["command"], Path(record["cwd"]), env)
                if name == "backend":
                    wait_http("http://127.0.0.1:" + str(config["backendPort"]) + "/actuator/health/readiness")
                elif name == "ai":
                    wait_http("http://127.0.0.1:" + str(config["aiPort"]) + "/api/health", 60)
            except Exception:
                errors.append(name)
        if errors:
            raise RuntimeError("Backup writers could not be restarted: " + ", ".join(errors) + "; data and backup files were preserved")
    print("Private MySQL + media + AI SQLite + secret sidecar backup saved: " + str(destination))
    print("Copy it to a separate disk/private storage. One laptop directory is not disaster recovery.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop", "status", "backup"))
    parser.add_argument("--runtime", type=Path, default=DEFAULT_RUNTIME)
    parser.add_argument("--mysql-home", default="/usr/local/mysql")
    parser.add_argument("--database-only", action="store_true")
    parser.add_argument("--no-frontend", action="store_true")
    parser.add_argument("--frontend-port", type=int, default=5173)
    parser.add_argument("--java-home")
    parser.add_argument("--ai-python")
    parser.add_argument("--node")
    parser.add_argument("--output", help="New private backup directory")
    args = parser.parse_args()
    if not 1024 <= args.frontend_port <= 65535:
        parser.error("Use an unprivileged frontend port")
    try:
        runtime, config = configuration(args.runtime)
        if args.action == "start":
            start_services(args, runtime, config)
        elif args.action == "stop":
            for name in ("frontend", "ai", "backend", "mysql-bootstrap", "mysql"):
                stop_process(runtime, name)
            print("Managed demo stopped. Database and files preserved.")
        elif args.action == "backup":
            backup(args, runtime, config)
        else:
            for name in ("mysql", "backend", "ai", "frontend"):
                record = runtime / (name + ".process.json")
                active = record.exists() and process_owned(json.loads(record.read_text()))
                print(name + ": " + ("running" if active else "stopped"))
            print("Runtime: " + str(runtime))
    except Exception as error:
        parser.exit(1, "Local demo refused: " + str(error) + "\n")


if __name__ == "__main__":
    main()
