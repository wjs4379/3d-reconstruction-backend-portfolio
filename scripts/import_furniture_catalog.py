#!/usr/bin/env python3
"""Prepare the six reviewed furniture GLBs locally, without AWS or network access.

Input files stay unchanged. Only fixed filenames with pinned size, SHA256 and
GLB headers are accepted. Existing destination files are never overwritten.
Set FURNITURE_LOCAL_DIRECTORY to the destination when starting the backend.
Model files belong in private runtime storage, never in Git or the bootJar.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import sys
import tempfile

MAX_BYTES = 1_048_576
CATALOG = (
    ('refridgerators-RB10FSR4ESRAA.glb', 595204, 'a470d2190db519fbdd5cb66e2583ac2bf6cb36aa62e1cde87e8ab517a3617643'),
    ('samsung_ac_363x1883x294.glb', 71360, '1d9f129f69370425321916c58a14adde02f8bdb64b629c4f11461bf92c5a6529'),
    ('samsung_fridge_912x1853x683.glb', 532700, '24202a148d31c0d6f4c3b642c2ef9806c82c16b03b9a7c04f0259121fb852110'),
    ('samsung_microwave_489x275x381_v2.glb', 172888, '6a33e519e3d51e2442b13f21096c8fa5a9d9e0283735c4e6fcdefe9705217fc1'),
    ('samsung_standbyme_716x1106x550base.glb', 634608, '39e3737504abdd382e9f47e2cf0a745b7526dde6268bb52f81bf09fd9268a79d'),
    ('samsung_washer_686x984x875_hires_v2.glb', 594800, 'd512db7b5e73bdd45ec57931e3c11f2228ff2e777f5dba34a3052819de7c6e8f'),
)


class CatalogError(Exception):
    pass


def directory(value, create=False):
    path = Path(value).expanduser()
    if not path.is_absolute() or '..' in path.parts:
        raise CatalogError('ABSOLUTE_NORMAL_DIRECTORY_REQUIRED')
    if path.is_symlink():
        raise CatalogError('SYMLINK_NOT_ALLOWED')
    if create:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.is_dir():
        raise CatalogError('DIRECTORY_REQUIRED')
    return path


def read_verified(root, definition):
    name, length, digest = definition
    if (Path(name).name != name or not name.endswith('.glb') or '/' in name or '\\' in name
            or type(length) is not int or not 20 <= length <= MAX_BYTES):
        raise CatalogError('INVALID_FIXED_CATALOG')
    path = root / name
    if path.is_symlink():
        raise CatalogError('SYMLINK_NOT_ALLOWED')
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'rb') as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size != length:
                raise CatalogError('MODEL_SIZE_OR_TYPE_MISMATCH')
            data = source.read(length + 1)
    except OSError:
        raise CatalogError('MODEL_FILE_UNAVAILABLE') from None
    if len(data) != length or hashlib.sha256(data).hexdigest() != digest:
        raise CatalogError('MODEL_CHECKSUM_MISMATCH')
    if struct.unpack('<4sII', data[:12]) != (b'glTF', 2, length):
        raise CatalogError('INVALID_GLB_HEADER')
    return data


def import_catalog(source_dir, output_dir=None):
    source = directory(source_dir)
    # Validate every source before creating or writing anything at the destination.
    assets = [(definition, read_verified(source, definition)) for definition in CATALOG]
    if output_dir is None:
        return {'success': True, 'verified': len(assets), 'copied': 0, 'existing': 0, 'networkRequests': 0}
    output = directory(output_dir, create=True)
    if output.stat().st_uid != os.getuid() or stat.S_IMODE(output.stat().st_mode) & 0o022:
        raise CatalogError('OUTPUT_OWNER_PERMISSIONS_REQUIRED')
    for definition, _ in assets:
        target = output / definition[0]
        if target.exists() or target.is_symlink():
            read_verified(output, definition)
    created = []
    existing = 0
    try:
        for definition, data in assets:
            target = output / definition[0]
            if target.exists():
                existing += 1
                continue
            fd, temporary = tempfile.mkstemp(prefix='.furniture-', dir=output)
            try:
                os.fchmod(fd, 0o600)
                with os.fdopen(fd, 'wb') as dest:
                    dest.write(data)
                    dest.flush()
                    os.fsync(dest.fileno())
                # Atomic exclusive publication; never replace a preexisting file.
                os.link(temporary, target, follow_symlinks=False)
                created.append(target)
            finally:
                Path(temporary).unlink(missing_ok=True)
        for definition, _ in assets:
            read_verified(output, definition)
    except BaseException:
        for path in created:
            path.unlink(missing_ok=True)
        raise
    return {'success': True, 'verified': len(assets), 'copied': len(created), 'existing': existing, 'networkRequests': 0}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', required=True, help='Directory containing the original six GLB files')
    parser.add_argument('--output-dir', help='Optional new runtime directory; omit for verification only')
    args = parser.parse_args(argv)
    try:
        result = import_catalog(args.source_dir, args.output_dir)
    except CatalogError as error:
        result = {'success': False, 'error': str(error), 'networkRequests': 0}
    except Exception:
        result = {'success': False, 'error': 'CATALOG_IMPORT_FAILED', 'networkRequests': 0}
    print(json.dumps(result, sort_keys=True))
    return 0 if result['success'] else 1


if __name__ == '__main__':
    sys.exit(main())
