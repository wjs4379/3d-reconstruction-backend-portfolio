"""Offline boundary tests; no real database, OAuth provider or server is started."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import socket
import stat
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

MODULE_PATH = Path(__file__).resolve().parents[1] / 'real_integration.py'
SPEC = importlib.util.spec_from_file_location('real_integration_under_test', MODULE_PATH)
cli = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cli)


class RealIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.runtime = self.base / 'runtime'
        with patch.object(cli.socket, 'socket', side_effect=AssertionError('prepare must be offline')), \
                patch.object(cli.subprocess, 'run', side_effect=AssertionError('prepare must not run commands')):
            self.prepared = cli.prepare(self.runtime)

    def values(self):
        return cli.configuration(self.runtime)[1]

    def update(self, **changes):
        values = self.values()
        values.update(changes)
        cli.write_private(self.runtime / cli.ENV_NAME, ''.join(k + '=' + v + '\n' for k, v in values.items()))

    def oauth(self):
        self.update(KAKAO_APP_ID='123456', KAKAO_REST_API_KEY='a' * 32, KAKAO_CLIENT_SECRET='b' * 32)

    def jar(self, extra=None, manifest=None):
        path = self.base / 'backend.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', manifest or (
                'Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\n'
                'Start-Class: com.hanyang.backend.BackendApplication\r\n'))
            archive.writestr('BOOT-INF/classes/com/hanyang/backend/BackendApplication.class', b'production-placeholder')
            # TestController is a legitimate production class, not a test fixture.
            archive.writestr('BOOT-INF/classes/com/hanyang/backend/TestController.class', b'production-placeholder')
            if extra:
                archive.writestr(extra, b'not-executed')
        path.chmod(0o600)
        return path

    def record(self):
        return {'version': 1, 'pid': 43210, 'uid': os.geteuid(),
                'birthTime': 'Fri Oct 16 19:00:00 2026',
                'command': ['/jdk/bin/java', '-jar', str(self.base / 'backend.jar')],
                'jarSha256': 'a' * 64, 'runtime': str(self.runtime)}

    def save_record(self, record=None):
        record = record or self.record()
        cli.write_private(self.runtime / cli.RECORD_NAME, json.dumps(record))
        return record

    def test_prepare_is_private_offline_and_separates_secrets(self):
        values = self.values()
        self.assertEqual(0o700, stat.S_IMODE(self.runtime.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((self.runtime / cli.ENV_NAME).stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE((self.runtime / 'media').stat().st_mode))
        self.assertEqual(cli.DB_URL, values['DB_URL'])
        self.assertEqual('gp_integration', values['DB_USERNAME'])
        self.assertEqual(3, len({values[k] for k in ['DB_PASSWORD', 'AI_SERVICE_KEY', 'JWT_SECRET']}))
        self.assertFalse(self.prepared['databaseCreated'])
        self.assertEqual(0, self.prepared['networkRequests'])
        output = json.dumps(self.prepared)
        for key in ['DB_PASSWORD', 'AI_SERVICE_KEY', 'JWT_SECRET']:
            self.assertNotIn(values[key], output)

    def test_prepare_never_overwrites_existing_settings(self):
        path = self.runtime / cli.ENV_NAME
        before = path.read_bytes()
        with self.assertRaisesRegex(cli.IntegrationError, 'ENV_ALREADY_EXISTS'):
            cli.prepare(self.runtime)
        self.assertEqual(before, path.read_bytes())

    def test_check_reports_only_missing_names_and_does_not_contact_network(self):
        with patch.object(cli, 'get_json', side_effect=AssertionError('check is offline')), \
                patch.object(cli.subprocess, 'run', side_effect=AssertionError('no DB or process commands')):
            result = cli.check(self.runtime)
        self.assertFalse(result['ok'])
        self.assertEqual(list(cli.KAKAO_REQUIRED), result['missingKeys'])
        self.assertFalse(result['oauthLoginVerified'])
        self.oauth()
        self.assertTrue(cli.check(self.runtime)['ok'])

    def test_env_permission_and_runtime_permission_are_not_silently_fixed(self):
        env = self.runtime / cli.ENV_NAME
        env.chmod(0o644)
        with self.assertRaisesRegex(cli.IntegrationError, 'PRIVATE_OWNER'):
            cli.check(self.runtime)
        self.assertEqual(0o644, stat.S_IMODE(env.stat().st_mode))
        env.chmod(0o600)
        self.runtime.chmod(0o755)
        with self.assertRaisesRegex(cli.IntegrationError, 'PRIVATE_OWNER'):
            cli.prepare(self.runtime)

    def test_symlink_parent_env_and_media_are_rejected(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.runtime, target_is_directory=True)
        with self.assertRaisesRegex(cli.IntegrationError, 'SYMLINK'):
            cli.check(alias)
        env = self.runtime / cli.ENV_NAME
        saved = self.runtime / 'saved-env'
        env.rename(saved)
        env.symlink_to(saved)
        with self.assertRaisesRegex(cli.IntegrationError, 'SYMLINK'):
            cli.check(self.runtime)
        env.unlink()
        saved.rename(env)
        (self.runtime / 'media').rmdir()
        (self.runtime / 'media').symlink_to(self.base)
        with self.assertRaisesRegex(cli.IntegrationError, 'SYMLINK'):
            cli.check(self.runtime)

    def test_hardlinked_env_is_rejected(self):
        os.link(self.runtime / cli.ENV_NAME, self.base / 'env-copy')
        with self.assertRaisesRegex(cli.IntegrationError, 'HARD_LINK'):
            cli.check(self.runtime)

    def test_literal_env_never_executes_shell_and_rejects_extra_keys(self):
        env = self.runtime / cli.ENV_NAME
        marker = self.base / 'must-not-exist'
        with env.open('a') as stream:
            stream.write('SPRING_APPLICATION_JSON=$(touch ' + str(marker) + ')\n')
        with self.assertRaisesRegex(cli.IntegrationError, 'UNKNOWN_DUPLICATE'):
            cli.check(self.runtime)
        self.assertFalse(marker.exists())

    def test_duplicate_keys_and_export_or_quoted_values_are_rejected(self):
        env = self.runtime / cli.ENV_NAME
        original = env.read_text()
        for extra in ['JWT_SECRET=abc\n', 'export JWT_SECRET=abc\n']:
            with self.subTest(extra=extra):
                cli.write_private(env, original + extra)
                with self.assertRaises(cli.IntegrationError):
                    cli.check(self.runtime)
        cli.write_private(env, original)
        self.update(KAKAO_APP_ID='"123456"')
        with self.assertRaisesRegex(cli.IntegrationError, 'INVALID_OAUTH'):
            cli.check(self.runtime)

    def test_original_or_remote_database_and_other_ports_are_refused(self):
        env = self.runtime / cli.ENV_NAME
        original = env.read_text()
        bad = {'DB_URL': 'jdbc:mysql://127.0.0.1:3327/gp_estate_demo', 'DB_USERNAME': 'gp_demo',
               'SERVER_PORT': '8082', 'SERVER_ADDRESS': '0.0.0.0',
               'APP_BACKEND_BASE_URL': 'https://example.invalid', 'AI_SERVER_BASE_URL': 'http://127.0.0.1:8001',
               'MEDIA_LOCAL_DIRECTORY': str(self.base), 'KAKAO_REDIRECT_URI': 'http://localhost:5173/oauth/callback/kakao'}
        for key, value in bad.items():
            with self.subTest(key=key):
                cli.write_private(env, original)
                self.update(**{key: value})
                with self.assertRaises(cli.IntegrationError):
                    cli.check(self.runtime)

    def test_three_ai_stages_and_worker_without_pipeline_refusal(self):
        for ai, worker in [('false', 'false'), ('true', 'false'), ('true', 'true')]:
            self.update(AI_PIPELINE_ENABLED=ai, OUTBOX_WORKER_ENABLED=worker)
            with patch.object(cli, 'get_json', side_effect=AssertionError('check must stay offline')):
                result = cli.check(self.runtime)
            self.assertEqual(ai == 'true', result['aiEnabled'])
            self.assertEqual(worker == 'true', result['workerEnabled'])
        self.update(AI_PIPELINE_ENABLED='false', OUTBOX_WORKER_ENABLED='true')
        with self.assertRaisesRegex(cli.IntegrationError, 'WORKER_REQUIRES_AI'):
            cli.check(self.runtime)

    def test_ai_readiness_uses_secret_header_nested_strict_booleans(self):
        self.update(AI_PIPELINE_ENABLED='true')
        values = self.values()
        good = {'status': 'ready', 'checks': {'worker': True, 'model': True, 'database': True}}
        with patch.object(cli, 'get_json', return_value=good) as get, patch.object(cli, 'require_owned_tunnel') as guard:
            cli.require_ai_ready(self.runtime, values)
        guard.assert_called_once_with(self.runtime)
        get.assert_called_once_with('http://127.0.0.1:18001/api/health/ready', values['AI_SERVICE_KEY'])
        bad = [{'status': 'ready', 'worker': True, 'model': True, 'database': True},
               {'status': 'not_ready', 'checks': good['checks']}]
        for field in ['worker', 'model', 'database']:
            for value in [1, 'true', False, None]:
                checks = dict(good['checks'], **{field: value})
                bad.append({'status': 'ready', 'checks': checks})
        for response in bad:
            with self.subTest(response=response), patch.object(cli, 'get_json', return_value=response), \
                    patch.object(cli, 'require_owned_tunnel'):
                with self.assertRaisesRegex(cli.IntegrationError, 'AI_NOT_STRICTLY_READY'):
                    cli.require_ai_ready(self.runtime, values)

    def test_ai_off_never_calls_ai_endpoint(self):
        with patch.object(cli, 'get_json', side_effect=AssertionError('AI is off')), \
                patch.object(cli, 'require_owned_tunnel', side_effect=AssertionError('AI is off')):
            cli.require_ai_ready(self.runtime, self.values())

    def tunnel_fixture(self):
        helper = Mock()
        config = {'knownHostsFile': str(self.runtime / 'ai_known_hosts'), 'expectedHostFingerprint': 'SHA256:verified'}
        command = ['/usr/bin/ssh', '-F', '/dev/null', '-N', '-T', '-L', '127.0.0.1:18001:127.0.0.1:8000',
                   '-R', '127.0.0.1:8083:127.0.0.1:8083', 'teammate@example.invalid']
        record = {'pid': 54321, 'argvSha256': hashlib.sha256(json.dumps(command).encode()).hexdigest()}
        helper.configuration.return_value = self.runtime, config, []
        helper.owned_record.return_value = record, True
        helper.ssh_argv.return_value = command
        helper.fingerprints.return_value = ['SHA256:verified']
        return helper, command, record

    def test_unmanaged_or_foreign_tunnel_never_receives_key_or_http_request(self):
        self.update(AI_PIPELINE_ENABLED='true')
        for failure in ['unmanaged', 'changed-argv', 'fingerprint', 'foreign-user', 'foreign-command', 'foreign-listener']:
            helper, command, record = self.tunnel_fixture()
            snapshot = (os.geteuid(), 'birth', ' '.join(command))
            if failure == 'unmanaged':
                helper.owned_record.return_value = record, False
            elif failure == 'changed-argv':
                record['argvSha256'] = 'f' * 64
            elif failure == 'fingerprint':
                helper.fingerprints.return_value = ['SHA256:other']
            elif failure == 'foreign-user':
                snapshot = (os.geteuid() + 1, snapshot[1], snapshot[2])
            elif failure == 'foreign-command':
                snapshot = (snapshot[0], snapshot[1], '/other/program')
            listener_error = cli.IntegrationError('private details') if failure == 'foreign-listener' else None
            with self.subTest(failure=failure), patch.object(cli, 'load_connection_helper', return_value=helper), \
                    patch.object(cli, 'process_snapshot', return_value=snapshot), \
                    patch.object(cli.os, 'getpgid', return_value=record['pid']), \
                    patch.object(cli, 'require_listener_owned', side_effect=listener_error), \
                    patch.object(cli, 'get_json') as get:
                with self.assertRaisesRegex(cli.IntegrationError, '^VERIFIED_MANAGED_AI_TUNNEL_REQUIRED$'):
                    cli.require_ai_ready(self.runtime, self.values())
                get.assert_not_called()

    def test_owned_tunnel_validates_current_config_host_and_actual_listener(self):
        helper, command, record = self.tunnel_fixture()
        with patch.object(cli, 'load_connection_helper', return_value=helper), \
                patch.object(cli, 'process_snapshot', return_value=(os.geteuid(), 'birth', ' '.join(command))), \
                patch.object(cli.os, 'getpgid', return_value=record['pid']), \
                patch.object(cli, 'require_listener_owned') as listener:
            cli.require_owned_tunnel(self.runtime)
        helper.configuration.assert_called_once_with(self.runtime)
        helper.owned_record.assert_called_once_with(self.runtime)
        listener.assert_called_once_with(record['pid'], port=18001)

    def test_enabled_worker_requires_gpu_and_real_checkpoints_on_owned_tunnel(self):
        self.update(AI_PIPELINE_ENABLED='true', OUTBOX_WORKER_ENABLED='true')
        values = self.values()
        ready = {'status': 'ready', 'checks': {'worker': True, 'model': True, 'database': True}}
        gpu = {'cudaAvailable': True, 'deviceCount': 1, 'devices': [{'index': 0}]}
        loaded = {'registration': {'strategy': 'da3_footprint', 'modelLoaded': True}}
        with patch.object(cli, 'require_owned_tunnel') as guard, \
                patch.object(cli, 'get_json', side_effect=[ready, gpu, loaded]) as get:
            cli.require_ai_ready(self.runtime, values)
        self.assertEqual(3, guard.call_count)
        self.assertEqual(['/api/health/ready', '/api/health/gpu', '/api/health/checkpoints'],
                         [call.args[0].removeprefix(values['AI_SERVER_BASE_URL']) for call in get.call_args_list])
        self.assertTrue(all(call.args[1] == values['AI_SERVICE_KEY'] for call in get.call_args_list))

    def test_ready_alone_cpu_or_mock_checkpoints_cannot_enable_worker(self):
        self.update(AI_PIPELINE_ENABLED='true', OUTBOX_WORKER_ENABLED='true')
        ready = {'status': 'ready', 'checks': {'worker': True, 'model': True, 'database': True}}
        good_gpu = {'cudaAvailable': True, 'deviceCount': 1, 'devices': [{'index': 0}]}
        bad_gpu = [{}, {'cudaAvailable': False, 'deviceCount': 0, 'devices': []},
                   {'cudaAvailable': True, 'deviceCount': True, 'devices': [{}]},
                   {'cudaAvailable': True, 'deviceCount': 1, 'devices': []},
                   {'cudaAvailable': True, 'deviceCount': 1, 'devices': ['not-a-device']}]
        for gpu in bad_gpu:
            with self.subTest(gpu=gpu), patch.object(cli, 'require_owned_tunnel'), \
                    patch.object(cli, 'get_json', side_effect=[ready, gpu]) as get:
                with self.assertRaisesRegex(cli.IntegrationError, 'REAL_GPU_REQUIRED'):
                    cli.require_ai_ready(self.runtime, self.values())
                self.assertEqual(2, get.call_count)
        for registration in [{}, {'strategy': 'mock', 'modelLoaded': True},
                             {'strategy': 'da3_footprint', 'modelLoaded': 1},
                             {'strategy': ['vggt'], 'modelLoaded': True}]:
            with self.subTest(registration=registration), patch.object(cli, 'require_owned_tunnel'), \
                    patch.object(cli, 'get_json', side_effect=[ready, good_gpu, {'registration': registration}]):
                with self.assertRaisesRegex(cli.IntegrationError, 'REAL_CHECKPOINT_REQUIRED'):
                    cli.require_ai_ready(self.runtime, self.values())

    def test_http_readiness_disables_proxies_redirects_and_redacts_error_body(self):
        opener = Mock()
        opener.open.side_effect = RuntimeError('private-value-not-to-be-printed')
        with patch.object(cli.urllib.request, 'build_opener', return_value=opener) as build:
            with self.assertRaisesRegex(cli.IntegrationError, '^READINESS_UNREACHABLE_OR_INVALID$'):
                cli.get_json('http://127.0.0.1:18001/api/health/ready', 'private-header')
        self.assertEqual({}, build.call_args.args[0].proxies)
        self.assertIsInstance(build.call_args.args[1], cli.NoRedirect)
        request = opener.open.call_args.args[0]
        self.assertEqual('private-header', request.get_header('X-ai-service-key'))
        self.assertIsNone(cli.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://external.invalid'))

    def test_boot_jar_accepts_production_controller_but_rejects_fixture_or_test_dependencies(self):
        path, digest = cli.validate_jar(self.jar())
        self.assertEqual(64, len(digest))
        self.assertEqual(self.base / 'backend.jar', path)
        bad = ['BOOT-INF/classes/com/hanyang/backend/smoke/EstateDemoServerKt.class',
               'BOOT-INF/classes/com/hanyang/backend/FooTest.class',
               'BOOT-INF/classes/com/hanyang/backend/FooTest$Inner.class',
               'BOOT-INF/classes/application-test.yaml', 'BOOT-INF/lib/junit-jupiter-5.jar',
               'BOOT-INF/lib/spring-boot-starter-test-4.jar', 'BOOT-INF/lib/h2-2.jar']
        for extra in bad:
            with self.subTest(entry=extra):
                with self.assertRaisesRegex(cli.IntegrationError, 'TEST_OR_FIXTURE'):
                    cli.validate_jar(self.jar(extra))

    def test_plain_jar_or_wrong_start_class_and_writable_jar_are_rejected(self):
        with self.assertRaisesRegex(cli.IntegrationError, 'PRODUCTION_BOOT_JAR'):
            cli.validate_jar(self.jar(manifest='Main-Class: example.Unsafe\nStart-Class: other.Application\n'))
        jar = self.jar()
        jar.chmod(0o666)
        with self.assertRaisesRegex(cli.IntegrationError, 'OWNED_PRODUCTION_JAR'):
            cli.validate_jar(jar)

    def test_child_env_excludes_ambient_java_spring_aws_and_proxy_overrides(self):
        unsafe = {'SPRING_APPLICATION_JSON': 'private', 'JAVA_TOOL_OPTIONS': '-Dserver.port=8082',
                  '_JAVA_OPTIONS': '-javaagent:bad', 'CLASSPATH': 'smoke', 'AWS_SECRET_ACCESS_KEY': 'private',
                  'HTTP_PROXY': 'http://external.invalid', 'SPRING_CONFIG_LOCATION': '/other/config'}
        with patch.dict(os.environ, unsafe):
            child = cli.child_environment(self.runtime, self.values())
        for key in unsafe:
            self.assertNotIn(key, child)
        self.assertEqual('8083', child['SERVER_PORT'])

    def test_start_refuses_missing_oauth_before_any_process_or_network(self):
        with patch.object(cli, 'validate_jar', side_effect=AssertionError('no jar operation')), \
                patch.object(cli, 'get_json', side_effect=AssertionError('no HTTP')), \
                patch.object(cli.subprocess, 'Popen', side_effect=AssertionError('no process')):
            with self.assertRaisesRegex(cli.IntegrationError, 'MISSING_OAUTH_KEYS'):
                cli.start(self.runtime, '/not/a/jar')

    def test_start_refuses_foreign_port_without_starting_or_stopping_anything(self):
        self.oauth()
        sock = Mock()
        sock.__enter__ = Mock(return_value=sock)
        sock.__exit__ = Mock(return_value=False)
        sock.bind.side_effect = OSError('busy')
        with patch.object(cli.socket, 'socket', return_value=sock), \
                patch.object(cli.subprocess, 'Popen') as spawn, patch.object(cli.os, 'kill') as kill:
            with self.assertRaisesRegex(cli.IntegrationError, '8083_ALREADY_IN_USE'):
                cli.start(self.runtime, self.jar())
        spawn.assert_not_called()
        kill.assert_not_called()
        sock.bind.assert_called_once_with(('127.0.0.1', 8083))

    def test_process_identity_requires_uid_birth_command_and_group(self):
        record = self.record()
        good = (os.geteuid(), record['birthTime'], ' '.join(record['command']))
        cases = [(good, record['pid']),
                 ((os.geteuid() + 1, good[1], good[2]), record['pid']),
                 ((good[0], 'other birth', good[2]), record['pid']),
                 ((good[0], good[1], good[2] + ' --server.port=8082'), record['pid']),
                 (good, record['pid'] + 1)]
        for index, (snapshot, group) in enumerate(cases):
            with patch.object(cli, 'process_snapshot', return_value=snapshot), patch.object(cli.os, 'getpgid', return_value=group):
                if index == 0:
                    cli.require_owned(record)
                else:
                    with self.assertRaisesRegex(cli.IntegrationError, 'IDENTITY_MISMATCH'):
                        cli.require_owned(record)

    def test_stop_never_signals_reused_or_foreign_pid(self):
        self.save_record()
        with patch.object(cli, 'process_exists', return_value=True), \
                patch.object(cli, 'process_snapshot', return_value=(os.geteuid(), 'changed birth', '/other/program')), \
                patch.object(cli.os, 'getpgid', return_value=43210), patch.object(cli.os, 'kill') as kill:
            with self.assertRaisesRegex(cli.IntegrationError, 'IDENTITY_MISMATCH'):
                cli.stop(self.runtime)
        kill.assert_not_called()
        self.assertTrue((self.runtime / cli.RECORD_NAME).exists())

    def test_stop_uses_only_verified_java_pid_and_no_force_kill(self):
        record = self.save_record()
        with patch.object(cli, 'process_exists', side_effect=[True, False, False]), \
                patch.object(cli, 'require_owned') as owned, patch.object(cli.os, 'kill') as kill:
            self.assertEqual('stopped', cli.stop(self.runtime)['state'])
        owned.assert_called_once_with(record)
        kill.assert_called_once_with(record['pid'], signal.SIGTERM)
        self.assertFalse((self.runtime / cli.RECORD_NAME).exists())

    def test_stale_record_cleanup_reports_no_signal_sent(self):
        self.save_record()
        with patch.object(cli, 'process_exists', return_value=False), patch.object(cli.os, 'kill') as kill:
            result = cli.stop(self.runtime)
        self.assertFalse(result['signalSent'])
        kill.assert_not_called()

    def test_readiness_listener_must_be_exact_child_pid(self):
        for stdout, returncode, accepted in [('p43210\nf22\n', 0, True), ('p123\n', 0, False),
                                            ('p43210\np123\n', 0, False), ('', 1, False)]:
            result = Mock(stdout=stdout, returncode=returncode)
            with self.subTest(stdout=stdout), patch.object(cli.subprocess, 'run', return_value=result), \
                    patch.object(cli.shutil, 'which', return_value='/usr/sbin/lsof'):
                if accepted:
                    cli.require_listener_owned(43210)
                else:
                    with self.assertRaisesRegex(cli.IntegrationError, 'LISTENER_IDENTITY'):
                        cli.require_listener_owned(43210)

    def test_start_launches_only_bootjar_with_env_secrets_and_private_log(self):
        self.oauth()
        values = self.values()
        jar = self.jar()
        command = ['/jdk/bin/java', '-jar', str(jar)]
        process = Mock(pid=43210)
        process.poll.return_value = None
        snapshot = (os.geteuid(), 'Fri Oct 16 19:00:00 2026', ' '.join(command))
        with patch.object(cli, 'require_free_port'), patch.object(cli, 'java_executable', return_value=Path(command[0])), \
                patch.object(cli.subprocess, 'Popen', return_value=process) as spawn, \
                patch.object(cli, 'process_snapshot', return_value=snapshot), \
                patch.object(cli.os, 'getpgid', return_value=43210), \
                patch.object(cli, 'get_json', return_value={'status': 'UP'}), \
                patch.object(cli, 'require_listener_owned') as listener:
            result = cli.start(self.runtime, jar)
        listener.assert_called_once_with(43210)
        self.assertEqual(command, spawn.call_args.args[0])
        self.assertTrue(spawn.call_args.kwargs['start_new_session'])
        self.assertEqual(values['DB_PASSWORD'], spawn.call_args.kwargs['env']['DB_PASSWORD'])
        self.assertEqual(0o600, stat.S_IMODE((self.runtime / 'backend.log').stat().st_mode))
        combined = json.dumps(result) + (self.runtime / cli.RECORD_NAME).read_text() + ' '.join(command)
        for key in ['DB_PASSWORD', 'JWT_SECRET', 'AI_SERVICE_KEY', 'KAKAO_REST_API_KEY', 'KAKAO_CLIENT_SECRET']:
            self.assertNotIn(values[key], combined)
        self.assertFalse(result['oauthLoginVerified'])

    def test_cli_error_does_not_echo_environment_or_raw_exception(self):
        private = self.values()['JWT_SECRET']
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch.object(cli, 'check', side_effect=OSError(private)):
            code = cli.main(['check', '--runtime', str(self.runtime)])
        self.assertEqual(2, code)
        self.assertNotIn(private, output.getvalue())
        self.assertEqual('LOCAL_OPERATION_FAILED_PRIVATE_DETAILS_SUPPRESSED', json.loads(output.getvalue())['error'])


if __name__ == '__main__':
    unittest.main()
