import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('import_furniture_catalog', Path(__file__).resolve().parents[1] / 'import_furniture_catalog.py')
catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(catalog)


class FurnitureImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir(mode=0o700)
        self.output = self.root / 'output'
        self.data = struct.pack('<4sII', b'glTF', 2, 24) + b'fixture-data'
        self.definition = ('fixture.glb', len(self.data), hashlib.sha256(self.data).hexdigest())
        (self.source / 'fixture.glb').write_bytes(self.data)
        self.patch = mock.patch.object(catalog, 'CATALOG', (self.definition,))
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.temp.cleanup()

    def test_verified_source_can_be_checked_without_output_or_network(self):
        result = catalog.import_catalog(self.source)
        self.assertEqual({'success': True, 'verified': 1, 'copied': 0, 'existing': 0, 'networkRequests': 0}, result)
        self.assertFalse(self.output.exists())

    def test_import_keeps_original_bytes_and_is_idempotent_without_overwrite(self):
        result = catalog.import_catalog(self.source, self.output)
        self.assertEqual(1, result['copied'])
        self.assertEqual(self.data, (self.output / 'fixture.glb').read_bytes())
        self.assertEqual(self.data, (self.source / 'fixture.glb').read_bytes())
        inode = (self.output / 'fixture.glb').stat().st_ino
        again = catalog.import_catalog(self.source, self.output)
        self.assertEqual(0, again['copied'])
        self.assertEqual(1, again['existing'])
        self.assertEqual(inode, (self.output / 'fixture.glb').stat().st_ino)
        self.assertEqual(0o600, (self.output / 'fixture.glb').stat().st_mode & 0o777)

    def test_missing_or_corrupt_source_does_not_create_destination(self):
        (self.source / 'fixture.glb').write_bytes(b'invalid')
        with self.assertRaises(catalog.CatalogError):
            catalog.import_catalog(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_corrupt_existing_target_is_never_overwritten(self):
        self.output.mkdir(mode=0o700)
        (self.output / 'fixture.glb').write_bytes(b'existing')
        with self.assertRaises(catalog.CatalogError):
            catalog.import_catalog(self.source, self.output)
        self.assertEqual(b'existing', (self.output / 'fixture.glb').read_bytes())

    def test_symlink_source_target_and_directory_are_rejected(self):
        actual = self.source / 'actual.glb'
        (self.source / 'fixture.glb').rename(actual)
        (self.source / 'fixture.glb').symlink_to(actual)
        with self.assertRaises(catalog.CatalogError):
            catalog.import_catalog(self.source, self.output)
        (self.source / 'fixture.glb').unlink()
        actual.rename(self.source / 'fixture.glb')
        self.output.mkdir(mode=0o700)
        (self.output / 'fixture.glb').symlink_to(self.source / 'fixture.glb')
        with self.assertRaises(catalog.CatalogError):
            catalog.import_catalog(self.source, self.output)
        linked = self.root / 'linked'
        linked.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(catalog.CatalogError):
            catalog.import_catalog(linked)

    def test_valid_checksum_does_not_make_invalid_glb_header_acceptable(self):
        data = b'bad!' + self.data[4:]
        (self.source / 'fixture.glb').write_bytes(data)
        with mock.patch.object(catalog, 'CATALOG', (('fixture.glb', len(data), hashlib.sha256(data).hexdigest()),)):
            with self.assertRaisesRegex(catalog.CatalogError, 'INVALID_GLB_HEADER'):
                catalog.import_catalog(self.source)

    def test_paths_and_oversized_definitions_are_rejected(self):
        with self.assertRaises(catalog.CatalogError):
            catalog.directory(Path('relative'))
        for definition in (('../fixture.glb', 24, 'a'*64), ('fixture.glb', catalog.MAX_BYTES+1, 'a'*64)):
            with self.assertRaises(catalog.CatalogError):
                catalog.read_verified(self.source, definition)

    def test_python_and_backend_catalog_pins_remain_identical(self):
        kotlin = (Path(__file__).resolve().parents[2] / 'src/main/kotlin/com/hanyang/backend/domain/furniture/FurnitureCatalog.kt').read_text()
        self.patch.stop()
        try:
            self.assertEqual(6, len(catalog.CATALOG))
            for name, length, digest in catalog.CATALOG:
                self.assertIn(f'"{name}", {length},', kotlin)
                self.assertIn(f'"{digest}"', kotlin)
        finally:
            self.patch.start()


if __name__ == '__main__':
    unittest.main()
