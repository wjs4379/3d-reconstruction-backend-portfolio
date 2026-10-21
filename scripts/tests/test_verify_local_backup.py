"""Adversarial backup boundaries and all-table restore checks; no live database required."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location('estate_restore_verification', SCRIPTS / 'verify_local_backup.py')
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)
from backup_database_fingerprint import database_fingerprint, literal_grant_identifier, literal_grant_pattern, require_same_fingerprint


class BackupFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.runtime = Path(self.tmp.name)
        self.runtime.chmod(0o700)
        self.parent = self.runtime / 'backups'
        self.parent.mkdir(mode=0o700)
        self.backup = self.parent / 'snapshot'
        self.backup.mkdir(mode=0o700)
        self.payload = b'private-test-media'
        self.write_file('backend.sql', b'CREATE TABLE media_assets (id BIGINT PRIMARY KEY);\n')
        self.write_file('local-secrets.json', b'{"testOnly":true}\n')
        self.write_archive()
        self.manifest = {'version': 1, 'database': 'gp_estate_demo', 'files': {}}
        self.rehash()
        self.config = {'database': 'gp_estate_demo', 'mysqlPort': 3327}

    def write_file(self, name, content):
        path = self.backup / name
        path.write_bytes(content)
        path.chmod(0o600)

    def write_archive(self, unsafe=False):
        path = self.backup / 'media.tar.gz'
        with tarfile.open(path, 'w:gz') as archive:
            folder = tarfile.TarInfo('media')
            folder.type = tarfile.DIRTYPE
            archive.addfile(folder)
            member = tarfile.TarInfo('media/assets/item.bin')
            member.size = len(self.payload)
            archive.addfile(member, io.BytesIO(self.payload))
            if unsafe:
                member = tarfile.TarInfo('media/external-link')
                member.type = tarfile.SYMTYPE
                member.linkname = '/outside'
                archive.addfile(member)
        path.chmod(0o600)

    def save_manifest(self):
        path = self.backup / 'backup-manifest.json'
        path.write_text(json.dumps(self.manifest))
        path.chmod(0o600)

    def rehash(self):
        for name in ('backend.sql', 'media.tar.gz', 'local-secrets.json'):
            value = (self.backup / name).read_bytes()
            self.manifest['files'][name] = {'bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest()}
        self.save_manifest()

    def refused_before_database(self, backup=None, **kwargs):
        with patch.object(verify, 'mysql_query') as query:
            with self.assertRaises((ValueError, OSError, json.JSONDecodeError)):
                verify.verify_backup(self.runtime, self.config, '/unused', backup or self.backup, **kwargs)
            query.assert_not_called()


class BackupBoundaryTests(BackupFixture):
    def test_valid_v1_backup_is_checked_without_db_access(self):
        with patch.object(verify, 'mysql_query') as query:
            _, _, manifest = verify.validate_backup(self.runtime, self.backup)
            self.assertEqual(manifest, self.manifest)
            query.assert_not_called()

    def test_raw_symlink_backup_is_not_resolved_into_trusted_directory(self):
        link = self.parent / 'alias'
        link.symlink_to(self.backup, target_is_directory=True)
        self.refused_before_database(link)

    def test_symlink_ancestor_is_rejected(self):
        link = self.parent / 'alias'
        link.symlink_to(self.parent, target_is_directory=True)
        self.refused_before_database(link / 'snapshot')

    def test_symlink_runtime_is_rejected(self):
        link = self.runtime / 'runtime-alias'
        link.symlink_to(self.runtime, target_is_directory=True)
        with patch.object(verify, 'mysql_query') as query:
            with self.assertRaises(ValueError):
                verify.verify_backup(link, self.config, '/unused', link / 'backups/snapshot')
            query.assert_not_called()

    def test_group_readable_backup_ancestor_is_rejected(self):
        self.parent.chmod(0o750)
        self.refused_before_database()

    def test_parent_traversal_is_rejected_before_resolving(self):
        self.refused_before_database(self.backup / '..' / 'snapshot')

    def test_manifest_mode_and_symbolic_link_are_checked(self):
        manifest = self.backup / 'backup-manifest.json'
        manifest.chmod(0o644)
        self.refused_before_database()
        manifest.chmod(0o600)
        target = self.runtime / 'manifest-copy.json'
        manifest.rename(target)
        manifest.symlink_to(target)
        self.refused_before_database()

    def test_non_regular_manifest_is_rejected(self):
        manifest = self.backup / 'backup-manifest.json'
        manifest.unlink()
        manifest.mkdir(mode=0o700)
        self.refused_before_database()

    def test_manifest_owner_is_checked(self):
        manifest = self.backup / 'backup-manifest.json'
        original = Path.lstat
        def lstat(path, *args, **kwargs):
            result = original(path, *args, **kwargs)
            if path == manifest:
                result = SimpleNamespace(st_mode=result.st_mode, st_uid=os.geteuid() + 1, st_size=result.st_size)
            return result
        with patch.object(Path, 'lstat', lstat):
            self.refused_before_database()

    def test_checksum_mismatch_is_rejected_before_db_access(self):
        self.write_file('backend.sql', b'x' * (self.manifest['files']['backend.sql']['bytes']))
        self.refused_before_database()

    def test_symlink_data_file_is_rejected(self):
        sql = self.backup / 'backend.sql'
        target = self.runtime / 'outside.sql'
        sql.rename(target)
        sql.symlink_to(target)
        self.refused_before_database()

    def test_malformed_manifest_paths_sizes_and_hashes_are_rejected(self):
        original = copy.deepcopy(self.manifest)
        for name, value in (
            ('../outside', {'bytes': 0, 'sha256': '0' * 64}),
            ('extra', {'bytes': True, 'sha256': '0' * 64}),
            ('extra', {'bytes': 0, 'sha256': 'not-a-sha'}),
        ):
            with self.subTest(name=name, value=value):
                self.manifest = copy.deepcopy(original)
                self.manifest['files'][name] = value
                self.save_manifest()
                self.refused_before_database()

    def test_unsafe_tar_link_is_rejected_even_with_valid_checksum(self):
        self.write_archive(unsafe=True)
        self.rehash()
        self.refused_before_database()

    def test_report_cannot_overwrite_original_backup_or_follow_parent_symlink(self):
        self.refused_before_database(report_path=self.backup / 'new-receipt.json')
        link = self.runtime / 'report-alias'
        link.symlink_to(self.runtime, target_is_directory=True)
        self.refused_before_database(report_path=link / 'new-receipt.json')

    def test_report_refuses_existing_file(self):
        report = self.runtime / 'existing-report.json'
        report.write_text('unchanged')
        report.chmod(0o600)
        self.refused_before_database(report_path=report)
        self.assertEqual(report.read_text(), 'unchanged')

    def test_streaming_sha_uses_bounded_reads(self):
        class Bounded(io.BytesIO):
            def read(self, size=-1):
                self_test.assertGreater(size, 0)
                self_test.assertLessEqual(size, 1024 * 1024)
                return super().read(size)
        self_test = self
        value = b'ab' * (1024 * 1024 + 17)
        self.assertEqual(verify.stream_sha(Bounded(value)), hashlib.sha256(value).hexdigest())


class LiteralGrantTests(unittest.TestCase):
    def test_partial_revokes_off_escapes_every_underscore(self):
        self.assertEqual(literal_grant_identifier('gp_estate_restore_a1', '0'), r'`gp\_estate\_restore\_a1`')
        self.assertEqual(literal_grant_pattern('gp_estate_demo', 'OFF'), r'gp\_estate\_demo')

    def test_partial_revokes_on_uses_literal_underscores_without_backslashes(self):
        self.assertEqual(literal_grant_identifier('gp_estate_restore_a1', '1'), '`gp_estate_restore_a1`')
        self.assertEqual(literal_grant_pattern('gp_estate_demo', 'ON'), 'gp_estate_demo')

    def test_unknown_semantics_and_unsafe_identifier_are_rejected(self):
        for setting in ('', 'unexpected', '2'):
            with self.assertRaises(ValueError):
                literal_grant_identifier('gp_estate_demo', setting)
        with self.assertRaises(ValueError):
            literal_grant_identifier("schema`; DROP DATABASE x", '0')


class FingerprintTests(unittest.TestCase):
    def query(self, sql):
        self.sql.append(sql)
        if 'TABLE_TYPE=\'BASE TABLE\'' in sql:
            return '\n'.join(self.tables)
        if 'SELECT COLUMN_NAME' in sql and 'information_schema.COLUMNS' in sql:
            return 'id\nparent_a\nparent_b'
        if "CONSTRAINT_NAME='PRIMARY'" in sql:
            return 'id'
        if sql.startswith('SHOW CREATE TABLE'):
            table = sql.split('.')[-1].rstrip(';')
            return table + '\tCREATE TABLE ' + table + ' (`id` bigint NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB'
        if 'REFERENCED_TABLE_NAME IS NOT NULL' in sql:
            return self.fks
        if 'NOT EXISTS' in sql:
            return self.orphans
        if sql.startswith('SELECT COUNT(*)'):
            return '2'
        raise AssertionError('Unexpected metadata query')

    def setUp(self):
        self.tables = ['table_' + str(i).zfill(2) for i in range(16)]
        self.sql = []
        self.fks = ''
        self.orphans = '0'
        self.rows = []

    def digest(self, sql):
        self.rows.append(sql)
        return hashlib.sha256(sql.replace('`restored`.', '').encode()).hexdigest()

    def test_all_sixteen_base_tables_have_rows_ddl_and_row_sha(self):
        result = database_fingerprint(self.query, 'restored', self.digest)
        self.assertEqual(result['tableCount'], 16)
        self.assertEqual(len(self.rows), 16)
        self.assertTrue(all(item['rows'] == 2 and len(item['rowSha256']) == 64 and len(item['ddlSha256']) == 64 for item in result['tables']))
        self.assertTrue(all('IF(' in sql and 'HEX(CAST(' in sql and 'ORDER BY `id`' in sql for sql in self.rows))

    def test_composite_fk_checks_all_non_null_columns_and_parent_join(self):
        self.fks = 'table_01\tfk_parent\tparent_a\trestored\ttable_00\tid\ntable_01\tfk_parent\tparent_b\trestored\ttable_00\tparent_a'
        result = database_fingerprint(self.query, 'restored', self.digest)
        self.assertEqual(result['foreignKeyConstraintsChecked'], 1)
        orphan = next(sql for sql in self.sql if 'NOT EXISTS' in sql)
        self.assertIn('c.`parent_a` IS NOT NULL AND c.`parent_b` IS NOT NULL', orphan)
        self.assertIn('p.`id`=c.`parent_a` AND p.`parent_a`=c.`parent_b`', orphan)

    def test_foreign_key_to_active_schema_is_refused(self):
        self.fks = 'table_01\tfk_parent\tparent_a\tgp_estate_demo\ttable_00\tid'
        with self.assertRaisesRegex(ValueError, 'only this restored schema'):
            database_fingerprint(self.query, 'restored', self.digest)

    def test_orphans_are_refused(self):
        self.fks = 'table_01\tfk_parent\tparent_a\trestored\ttable_00\tid'
        self.orphans = '1'
        with self.assertRaisesRegex(ValueError, 'orphaned'):
            database_fingerprint(self.query, 'restored', self.digest)

    def test_non_primary_key_tables_sort_encoded_full_rows(self):
        original = self.query
        def query(sql):
            return '' if "CONSTRAINT_NAME='PRIMARY'" in sql else original(sql)
        database_fingerprint(query, 'restored', self.digest)
        self.assertTrue(all('ORDER BY IF(' in sql for sql in self.rows))

    def test_same_counts_but_different_row_sha_is_a_mismatch(self):
        fingerprint = database_fingerprint(self.query, 'restored', self.digest)
        altered = copy.deepcopy(fingerprint)
        altered['tables'][-1]['rowSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'differ'):
            require_same_fingerprint(fingerprint, altered)


class LimitedRestoreTests(BackupFixture):
    fingerprint = {'format': 1, 'tableCount': 1, 'tables': [{'table': 'media_assets', 'rows': 1, 'rowSha256': 'a' * 64, 'ddlSha256': 'b' * 64}],
                   'foreignKeys': [], 'foreignKeyConstraintsChecked': 0, 'foreignKeyViolations': 0, 'foreignKeysReferenceOnlySnapshotSchema': True}

    def root_query(self, runtime, config, home, sql, root=False):
        self.assertTrue(root, 'Only provisioning/cleanup should use the root helper in default restore')
        self.root_sql.append(sql)
        if 'partial_revokes' in sql:
            return self.partial
        if 'FROM mysql.user' in sql:
            return '0'
        if 'HEX(Db)' in sql:
            return literal_grant_pattern('gp_estate_restore_' + 'a' * 12, self.partial).encode().hex().upper()
        return ''

    def client_run(self, command, **kwargs):
        self.commands.append((command, kwargs))
        self.assertTrue(any(part.startswith('--defaults-file=') for part in command))
        self.assertIn('--database=gp_estate_restore_' + 'a' * 12, command)
        self.assertIn('--binary-mode=1', command)
        self.assertIn('--local-infile=0', command)
        self.assertFalse(any('password=' in part for part in command))
        client = Path(next(part.split('=', 1)[1] for part in command if part.startswith('--defaults-file=')))
        settings = client.read_text()
        self.assertIn('user=gp_restore_' + 'a' * 12, settings)
        self.assertNotIn('user=root', settings)
        self.assertEqual(client.stat().st_mode & 0o777, 0o600)
        if 'stdin' in kwargs:
            return SimpleNamespace(returncode=self.import_returncode)
        checksum = hashlib.sha256(self.payload).hexdigest()
        return SimpleNamespace(returncode=0, stdout='assets/item.bin\t' + checksum + '\t' + str(len(self.payload)) + '\n')

    def execute(self, *, compare_active=False, expected=None):
        self.root_sql, self.commands = [], []
        self.partial = '0'
        self.import_returncode = 0
        fingerprint = expected if expected is not None else self.fingerprint
        with patch.object(verify.secrets, 'token_hex', side_effect=['a' * 12, 'b' * 64]), \
             patch.object(verify, 'mysql_query', side_effect=self.root_query), \
             patch.object(verify, 'executable', return_value='/mysql/bin/mysql'), \
             patch.object(verify.subprocess, 'run', side_effect=self.client_run), \
             patch.object(verify, 'database_fingerprint', return_value=fingerprint):
            return verify.verify_backup(self.runtime, self.config, '/mysql', self.backup, compare_active=compare_active)

    def test_legacy_backup_collects_all_table_proof_without_active_comparison(self):
        result = self.execute()
        self.assertFalse(result['snapshotFingerprintCompared'])
        self.assertEqual(result['snapshotFingerprintStatus'], 'not-present-in-legacy-v1-manifest')
        self.assertFalse(result['activeDatabaseComparisonRequested'])
        self.assertEqual(result['verifiedMediaFiles'], 1)
        self.assertTrue(result['temporaryAccountRemoved'])
        report = Path(result['reportPath'])
        self.assertFalse(report.is_relative_to(self.backup))
        self.assertEqual(report.stat().st_mode & 0o777, 0o600)
        self.assertTrue(self.root_sql[-1].startswith('DROP USER IF EXISTS'))
        self.assertTrue(any(r'`gp\_estate\_restore\_aaaaaaaaaaaa`.*' in sql for sql in self.root_sql if sql.startswith('GRANT')))
        self.assertEqual(len(list(self.runtime.glob('restore-client-*'))), 0)

    def test_active_comparison_requires_explicit_option(self):
        self.root_sql, self.commands = [], []
        self.partial, self.import_returncode = '0', 0
        with patch.object(verify.secrets, 'token_hex', side_effect=['a' * 12, 'b' * 64]), \
             patch.object(verify, 'mysql_query', side_effect=self.root_query), \
             patch.object(verify, 'executable', return_value='/mysql/bin/mysql'), \
             patch.object(verify.subprocess, 'run', side_effect=self.client_run), \
             patch.object(verify, 'database_fingerprint', return_value=self.fingerprint) as fingerprint:
            result = verify.verify_backup(self.runtime, self.config, '/mysql', self.backup, compare_active=True)
        self.assertEqual(fingerprint.call_count, 2)
        self.assertTrue(result['activeDatabaseComparisonRequested'])
        self.assertTrue(result['activeDatabaseFingerprintMatched'])

    def test_snapshot_baseline_is_compared_when_present(self):
        self.manifest['databaseFingerprint'] = self.fingerprint
        self.save_manifest()
        result = self.execute()
        self.assertTrue(result['snapshotFingerprintCompared'])
        self.assertEqual(result['snapshotFingerprintStatus'], 'matched')

    def test_snapshot_row_hash_mismatch_cleans_account_without_success_receipt(self):
        self.manifest['databaseFingerprint'] = copy.deepcopy(self.fingerprint)
        self.manifest['databaseFingerprint']['tables'][0]['rowSha256'] = '0' * 64
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'differ'):
            self.execute()
        self.assertTrue(self.root_sql[-1].startswith('DROP USER IF EXISTS'))
        self.assertFalse(list((self.runtime / 'restore-verifications').glob('*.json')))

    def test_failed_limited_import_still_drops_temp_account(self):
        self.root_sql, self.commands = [], []
        self.partial, self.import_returncode = '0', 1
        with patch.object(verify.secrets, 'token_hex', side_effect=['a' * 12, 'b' * 64]), \
             patch.object(verify, 'mysql_query', side_effect=self.root_query), \
             patch.object(verify, 'executable', return_value='/mysql/bin/mysql'), \
             patch.object(verify.subprocess, 'run', side_effect=self.client_run):
            with self.assertRaisesRegex(RuntimeError, 'no root import'):
                verify.verify_backup(self.runtime, self.config, '/mysql', self.backup)
        self.assertTrue(self.root_sql[-1].startswith('DROP USER IF EXISTS'))
        self.assertFalse(list((self.runtime / 'restore-verifications').glob('*.json')))

    def test_unexpected_stored_grant_prevents_import_and_removes_account(self):
        self.root_sql, self.commands = [], []
        self.partial, self.import_returncode = '0', 0
        original = self.root_query
        def root_query(*args, **kwargs):
            return 'bad-pattern' if 'HEX(Db)' in args[3] else original(*args, **kwargs)
        with patch.object(verify.secrets, 'token_hex', side_effect=['a' * 12, 'b' * 64]), \
             patch.object(verify, 'mysql_query', side_effect=root_query), patch.object(verify.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'exact schema'):
                verify.verify_backup(self.runtime, self.config, '/mysql', self.backup)
            run.assert_not_called()
        self.assertTrue(self.root_sql[-1].startswith('DROP USER IF EXISTS'))


if __name__ == '__main__':
    unittest.main()
