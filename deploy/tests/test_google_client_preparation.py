"""Google's public client ID stays optional and identical in backend and web build."""
import os
import re
import unittest
from unittest.mock import patch

from test_aws_preparation import ROOT, config, valid_environment
from test_cloudfront_preparation import cloudfront_environment
from test_cloudfront_local_preparation import local_environment
from test_cloudfront_preview_preparation import preview_environment

PROFILES = ('compose.aws.yaml', 'compose.aws-cloudfront.yaml',
            'compose.aws-cloudfront-local.yaml', 'compose.prod.yaml')
PUBLIC_CLIENT_ID = 'fixture-public-web-client.apps.googleusercontent.com'


def service_scalars(document, service, indentation):
    """Read direct scalar entries at a known Compose nesting level, without new YAML dependencies."""
    lines = document.splitlines()
    start = lines.index('  ' + service + ':') + 1
    entries = []
    for line in lines[start:]:
        if line.strip() and not line.lstrip().startswith('#') and len(line) - len(line.lstrip()) <= 2:
            break
        match = re.fullmatch(' ' * indentation + r'([A-Z][A-Z0-9_]*):\s*(.+)', line)
        if match:
            entries.append(match.groups())
    if len(entries) != len(dict(entries)):
        raise AssertionError('Duplicate configuration key')
    return dict(entries)


def optional_substitution(expression, environment):
    match = re.fullmatch(r'\$\{([A-Z][A-Z0-9_]*):-([^}]*)\}', expression)
    if not match:
        raise AssertionError('Expected an explicit optional Compose value')
    return environment.get(match[1]) or match[2]


class GoogleClientPreparationTests(unittest.TestCase):
    def test_every_web_build_uses_the_same_optional_server_client_id(self):
        for name in PROFILES:
            with self.subTest(profile=name):
                document = (ROOT / name).read_text()
                app_document = (ROOT / 'compose.yaml').read_text() if name == 'compose.prod.yaml' else document
                server = service_scalars(app_document, 'app', 6)['GOOGLE_CLIENT_ID']
                browser = service_scalars(document, 'web', 8).get('VITE_GOOGLE_CLIENT_ID')
                self.assertIsNotNone(browser, 'The frontend build must receive the server public Google client ID')
                self.assertEqual(server, browser)
                for supplied in (None, '', PUBLIC_CLIENT_ID):
                    values = {'VITE_GOOGLE_CLIENT_ID': 'unrelated-browser-override.apps.googleusercontent.com'}
                    if supplied is not None:
                        values['GOOGLE_CLIENT_ID'] = supplied
                    self.assertEqual(supplied or '', optional_substitution(server, values))
                    self.assertEqual(supplied or '', optional_substitution(browser, values))

    def test_examples_keep_one_optional_input_instead_of_an_independent_browser_id(self):
        for name in ('.env.example', 'deploy/aws.env.example', 'deploy/aws-cloudfront.env.example',
                     'deploy/aws-cloudfront-local.env.example', 'deploy/aws-cloudfront-local.preview.env.example'):
            with self.subTest(example=name):
                values = config.read_env(ROOT / name)
                self.assertEqual('', values['GOOGLE_CLIENT_ID'])
                self.assertNotIn('VITE_GOOGLE_CLIENT_ID', values)

    def test_google_does_not_relax_existing_kakao_or_preview_requirements(self):
        for mode, fixture in (('tls', valid_environment), ('cloudfront', cloudfront_environment),
                              ('cloudfront-local', local_environment)):
            for value in ('', PUBLIC_CLIENT_ID):
                with self.subTest(mode=mode, googleEnabled=bool(value)):
                    env = dict(fixture(), GOOGLE_CLIENT_ID=value)
                    self.assertEqual([], config.validate(env, check_files=False, mode=mode))
                    env['KAKAO_CLIENT_SECRET'] = ''
                    self.assertTrue(any(message.startswith('KAKAO_CLIENT_SECRET:') for message in
                                        config.validate(env, check_files=False, mode=mode)))
        errors = config.validate(dict(preview_environment(), GOOGLE_CLIENT_ID=PUBLIC_CLIENT_ID),
                                 check_files=False, mode='cloudfront-local', preview_without_oauth=True)
        self.assertTrue(any(message.startswith('GOOGLE_CLIENT_ID:') for message in errors))
        self.assertNotIn(PUBLIC_CLIENT_ID, '\n'.join(errors))

    def test_exported_server_google_override_is_still_rejected_without_value_disclosure(self):
        for mode, fixture in (('tls', valid_environment), ('cloudfront', cloudfront_environment),
                              ('cloudfront-local', local_environment)):
            with self.subTest(mode=mode):
                env = dict(fixture(), GOOGLE_CLIENT_ID=PUBLIC_CLIENT_ID)
                other = 'different-exported-public-client.apps.googleusercontent.com'
                with patch.dict(os.environ, {'GOOGLE_CLIENT_ID': other}, clear=True):
                    errors = config.validate_process_environment(env, mode)
                self.assertTrue(any(message.startswith('GOOGLE_CLIENT_ID:') for message in errors))
                self.assertNotIn(other, '\n'.join(errors))


if __name__ == '__main__':
    unittest.main()
