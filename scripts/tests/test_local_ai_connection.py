import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location('local_ai_connection', Path(__file__).parents[1] / 'local_ai_connection.py')
m = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)


class ConnectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.runtime = Path(self.temp.name).resolve()
        os.chmod(self.runtime, 0o700)
        # Generate a real local key; this performs no network operation.
        m.prepare(self.runtime)

    def tearDown(self):
        self.temp.cleanup()

    def configure(self, **changes):
        p = self.runtime / 'ai-connection.json'
        c = json.loads(p.read_text())
        c.update(sshHost='ai-pc.example', sshUser='ai_user', expectedHostFingerprint='SHA256:' + 'A' * 43,
                 remoteForwardLoopbackOnlyConfirmed=True)
        c.update(changes)
        m.write_private(p, json.dumps(c))
        return c

    def test_prepare_preserves_existing_identity_and_reports_no_transfer(self):
        key = (self.runtime / 'ai_ed25519').read_bytes()
        with self.assertRaisesRegex(m.ConnectionError, 'ALREADY_EXISTS'):
            m.prepare(self.runtime)
        self.assertEqual(key, (self.runtime / 'ai_ed25519').read_bytes())
        _, c, missing = m.configuration(self.runtime, complete=False)
        self.assertEqual(set(missing), {'sshHost', 'sshUser', 'expectedHostFingerprint',
                                        'remoteForwardLoopbackOnlyConfirmed'})
        self.assertEqual(c['localAiPort'], 18001)

    def test_private_files_and_symlinks_are_rejected(self):
        p = self.runtime / 'ai-connection.json'
        os.chmod(p, 0o640)
        with self.assertRaisesRegex(m.ConnectionError, 'PERMISSIONS'):
            m.configuration(self.runtime)
        os.chmod(p, 0o600)
        link = self.runtime / 'linked'
        link.symlink_to(p)
        with self.assertRaisesRegex(m.ConnectionError, 'SYMLINK'):
            m.read_json(link)

    def test_unknown_fields_ports_types_and_shell_tokens_are_rejected(self):
        for change in [{'localAiPort': 8082}, {'remoteAiPort': 8083}, {'sshPort': True},
                       {'sshUser': 'ai; touch /tmp/example'}, {'sshHost': '-oProxyCommand=sh'},
                       {'expectedHostFingerprint': ['invalid']}, {'identityFile': '/tmp/key'},
                       {'unrecognizedCommand': 'anything'}]:
            with self.subTest(change=change):
                # Reset before each invalid independent input.
                p = self.runtime / 'ai-connection.json'
                c = json.loads(p.read_text())
                c.pop('unrecognizedCommand', None)
                c.update(sshHost='ai-pc.example', sshUser='ai_user', sshPort=22, remoteAiPort=8000,
                         localAiPort=18001, remoteCallbackPort=8083,
                         expectedHostFingerprint='SHA256:' + 'A' * 43,
                         identityFile=str(self.runtime / 'ai_ed25519'))
                c.update(change)
                m.write_private(p, json.dumps(c))
                with self.assertRaises(m.ConnectionError):
                    m.configuration(self.runtime)

    def test_ssh_command_has_only_loopback_forwards_and_explicit_trust(self):
        c = self.configure()
        args = m.ssh_argv(c)
        self.assertEqual(args[args.index('-L') + 1], '127.0.0.1:18001:127.0.0.1:8000')
        self.assertEqual(args[args.index('-R') + 1], '127.0.0.1:8083:127.0.0.1:8083')
        for setting in ['StrictHostKeyChecking=yes', 'ForwardAgent=no', 'BatchMode=yes',
                        'IdentitiesOnly=yes', 'IdentityAgent=none', 'ExitOnForwardFailure=yes',
                        'ProxyCommand=none', 'ProxyJump=none']:
            self.assertIn(setting, args)
        self.assertNotIn('ClearAllForwardings=yes', args)
        self.assertNotIn('AI_SERVICE_KEY', ' '.join(args))

    def test_unconfirmed_host_key_is_never_saved(self):
        self.configure()
        scan = Mock(returncode=0, stdout='ai-pc.example ssh-ed25519 AAAATEST\n')
        with patch.object(m.subprocess, 'run', return_value=scan), \
                patch.object(m, 'fingerprints', return_value=['SHA256:' + 'B' * 43]):
            with self.assertRaisesRegex(m.ConnectionError, 'FINGERPRINT_MISMATCH'):
                m.trust_host(self.runtime)
        self.assertEqual((self.runtime / 'ai_known_hosts').read_text(), '')

    def test_start_with_missing_information_never_connects(self):
        with patch.object(m.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(m.ConnectionError, 'MISSING'):
                m.start(self.runtime)
            popen.assert_not_called()

    def test_remote_loopback_policy_must_be_confirmed_before_connecting(self):
        self.configure(remoteForwardLoopbackOnlyConfirmed=False)
        with patch.object(m.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(m.ConnectionError, 'remoteForwardLoopbackOnlyConfirmed'):
                m.start(self.runtime)
            popen.assert_not_called()

    def test_start_refuses_a_port_owned_by_another_service(self):
        c = self.configure()
        with patch.object(m, 'fingerprints', return_value=[c['expectedHostFingerprint']]), \
                patch.object(m, 'owned_record', return_value=(None, False)), \
                patch.object(m, 'listening', return_value=True), \
                patch.object(m.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(m.ConnectionError, 'PORT_ALREADY_IN_USE'):
                m.start(self.runtime)
            popen.assert_not_called()

    def test_stop_never_signals_a_reused_or_unowned_pid(self):
        with patch.object(m, 'owned_record', return_value=({'pid': 1234}, False)), \
                patch.object(m, 'process_snapshot', return_value='another process'), \
                patch.object(m.os, 'kill') as kill:
            with self.assertRaisesRegex(m.ConnectionError, 'UNOWNED'):
                m.stop(self.runtime)
            kill.assert_not_called()

    def test_other_program_with_same_snapshot_is_not_treated_as_ssh(self):
        record = {'pid': 1234, 'processSnapshot': 'Fri Oct 16 19:00:00 2026 /usr/bin/java -jar app.jar'}
        m.write_private(self.runtime / 'ai-ssh-process.json', json.dumps(record))
        with patch.object(m, 'process_snapshot', return_value=record['processSnapshot']):
            self.assertFalse(m.owned_record(self.runtime)[1])

    def test_check_never_sends_key_to_an_unmanaged_local_port(self):
        with patch.object(m, 'owned_record', return_value=(None, False)), \
                patch.object(m, 'backend_ai_key') as read_key, \
                patch.object(m, 'get_json', return_value={'status': 'UP'}) as get:
            result = m.check(self.runtime)
        read_key.assert_not_called()
        self.assertEqual(get.call_count, 1)
        self.assertEqual(get.call_args[0][0], 'http://127.0.0.1:8083/actuator/health/readiness')
        self.assertFalse(result['success'])
        self.assertFalse(result['endToEndVerified'])

    def test_ready_requires_actual_booleans_and_never_claims_callback(self):
        self.configure()
        for ready, expected in [({'status': 'ready', 'checks': {'worker': True, 'model': True, 'database': True}}, True),
                                ({'status': 'ready', 'checks': {'worker': 'true', 'model': True, 'database': True}}, False),
                                ({'status': 'ready', 'checks': {'worker': True, 'model': True, 'database': False}}, False),
                                ({'status': 'ready', 'checks': 'malformed'}, False)]:
            with patch.object(m, 'require_owned_tunnel', return_value={'pid': 1234}), \
                    patch.object(m, 'backend_ai_key', return_value='private-test-key-not-output'), \
                    patch.object(m, 'get_json', side_effect=[{'status': 'UP'}, ready]):
                result = m.check(self.runtime)
            self.assertIs(result['success'], expected)
            self.assertFalse(result['callbackPostVerified'])
            self.assertFalse(result['aiToBackendPhotoReadVerified'])

    def tunnel_record(self, c):
        return {'pid': 12345, 'argvSha256': hashlib.sha256(json.dumps(m.ssh_argv(c)).encode()).hexdigest()}

    def test_verified_tunnel_requires_config_hash_host_live_identity_and_listener(self):
        c = self.configure()
        record = self.tunnel_record(c)
        identity = Mock(returncode=0, stdout=str(os.getuid()) + ' ' + ' '.join(m.ssh_argv(c)))
        listener = Mock(returncode=0, stdout='p12345\nf11\n')
        with patch.object(m, 'owned_record', return_value=(record, True)), \
                patch.object(m, 'fingerprints', return_value=[c['expectedHostFingerprint']]), \
                patch.object(m.os, 'getpgid', return_value=record['pid']), \
                patch.object(m.subprocess, 'run', side_effect=[identity, listener]) as run:
            self.assertEqual(record, m.require_owned_tunnel(self.runtime))
        self.assertIn('-iTCP:18001', run.call_args_list[1].args[0])

    def test_check_does_not_read_or_send_key_for_any_foreign_tunnel_boundary(self):
        c = self.configure()
        for fault in ['unmanaged', 'config-hash', 'host-key', 'uid', 'command', 'group', 'listener', 'multiple-listeners']:
            record = self.tunnel_record(c)
            owned, fingerprint = fault != 'unmanaged', c['expectedHostFingerprint']
            uid, command, group, listeners = os.getuid(), ' '.join(m.ssh_argv(c)), record['pid'], 'p12345\n'
            if fault == 'config-hash':
                record['argvSha256'] = 'f' * 64
            elif fault == 'host-key':
                fingerprint = 'SHA256:' + 'B' * 43
            elif fault == 'uid':
                uid += 1
            elif fault == 'command':
                command += ' --unmanaged-option'
            elif fault == 'group':
                group += 1
            elif fault == 'listener':
                listeners = 'p54321\n'
            elif fault == 'multiple-listeners':
                listeners += 'p54321\n'
            responses = [Mock(returncode=0, stdout=str(uid) + ' ' + command), Mock(returncode=0, stdout=listeners)]
            with self.subTest(fault=fault), patch.object(m, 'owned_record', return_value=(record, owned)), \
                    patch.object(m, 'fingerprints', return_value=[fingerprint]), \
                    patch.object(m.os, 'getpgid', return_value=group), \
                    patch.object(m.subprocess, 'run', side_effect=responses), \
                    patch.object(m, 'backend_ai_key') as read_key, \
                    patch.object(m, 'get_json', return_value={'status': 'UP'}) as get:
                result = m.check(self.runtime)
            read_key.assert_not_called()
            get.assert_called_once_with('http://127.0.0.1:8083/actuator/health/readiness', None)
            self.assertFalse(result['managedSshRunning'])
            self.assertFalse(result['success'])

    def test_check_rechecks_listener_after_key_read_before_authenticated_get(self):
        self.configure()
        with patch.object(m, 'require_owned_tunnel', side_effect=[{'pid': 12345}, m.ConnectionError('private-details')]) as guard, \
                patch.object(m, 'backend_ai_key', return_value='private-key-never-sent') as read_key, \
                patch.object(m, 'get_json', return_value={'status': 'UP'}) as get:
            result = m.check(self.runtime)
        self.assertEqual(2, guard.call_count)
        read_key.assert_called_once_with(self.runtime)
        get.assert_called_once_with('http://127.0.0.1:8083/actuator/health/readiness', None)
        self.assertFalse(result['managedSshRunning'])
        self.assertFalse(result['success'])

    def test_start_checks_actual_listener_before_reporting_managed(self):
        c = self.configure()
        proc = Mock(pid=12345)
        proc.poll.return_value = None
        with patch.object(m, 'fingerprints', return_value=[c['expectedHostFingerprint']]), \
                patch.object(m, 'owned_record', return_value=(None, False)), \
                patch.object(m, 'listening', side_effect=[False, True, True]), \
                patch.object(m.subprocess, 'Popen', return_value=proc), \
                patch.object(m, 'process_snapshot', return_value='owned process snapshot'), \
                patch.object(m, 'require_owned_tunnel', return_value={'pid': 12345}) as guard:
            result = m.start(self.runtime)
        guard.assert_called_once_with(self.runtime)
        self.assertTrue(result['managed'])
        self.assertFalse(result['aiReadyVerified'])

    def test_start_foreign_listener_guard_failure_only_cleans_up_its_own_child(self):
        c = self.configure()
        proc = Mock(pid=12345)
        proc.poll.return_value = None
        with patch.object(m, 'fingerprints', return_value=[c['expectedHostFingerprint']]), \
                patch.object(m, 'owned_record', return_value=(None, False)), \
                patch.object(m, 'listening', side_effect=[False, True, True]), \
                patch.object(m.subprocess, 'Popen', return_value=proc), \
                patch.object(m, 'process_snapshot', return_value='owned process snapshot'), \
                patch.object(m, 'require_owned_tunnel', side_effect=m.ConnectionError('VERIFIED_MANAGED_SSH_REQUIRED')), \
                patch.object(m.os, 'kill') as kill:
            with self.assertRaisesRegex(m.ConnectionError, 'VERIFIED_MANAGED_SSH_REQUIRED'):
                m.start(self.runtime)
        proc.terminate.assert_called_once_with()
        kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()
