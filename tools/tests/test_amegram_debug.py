"""Synthetic configuration fixtures only: no APK, service access or real API values."""
import copy
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('debug', Path(__file__).resolve().parents[1] / 'amegram_debug.py')
debug = importlib.util.module_from_spec(spec)
spec.loader.exec_module(debug)


def firebase_document():
    return {
        'project_info': {'project_id': 'unit-test-project', 'project_number': '123'},
        'client': [{'client_info': {'android_client_info': {'package_name': debug.PACKAGE},
                                    'mobilesdk_app_id': '1:123:android:abc'},
                    'api_key': [{'current_key': 'firebase-unit-fixture'}]}],
    }


def merged_manifest():
    return ET.fromstring('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.umi.amegram.beta">
        <uses-permission android:name="android.permission.INTERNET" />
        <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
        <application>
          <provider android:name="com.google.firebase.provider.FirebaseInitProvider"
            android:authorities="dev.umi.amegram.beta.firebaseinitprovider" android:exported="false" />
          <service android:name="org.telegram.messenger.GcmPushListenerService" android:exported="false">
            <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
          </service>
          <meta-data android:name="firebase_analytics_collection_deactivated" android:value="true" />
          <meta-data android:name="google_analytics_adid_collection_enabled" android:value="false" />
          <meta-data android:name="com.google.android.maps.v2.API_KEY" android:value="@string/amegram_google_maps_api_key" />
        </application></manifest>''')


class DebugConfigurationTest(unittest.TestCase):
    def test_rejected_credential_arguments_are_not_echoed(self):
        output = io.StringIO()
        with contextlib.redirect_stderr(output), self.assertRaises(SystemExit) as error:
            debug.main(['build', '--api-hash', 'private-cli-fixture'])
        self.assertEqual(error.exception.code, 2)
        self.assertNotIn('private-cli-fixture', output.getvalue())

    def test_build_stops_before_gradle_when_owner_api_is_missing(self):
        with patch.object(debug.os, 'environ', {}), patch.object(debug, 'gradle') as gradle, \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(debug.main(['build']), 1)
            gradle.assert_not_called()

    def test_malformed_private_json_error_does_not_echo_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / debug.FIREBASE_FILE
            path.parent.mkdir(parents=True)
            path.write_text('{"private-fixture": invalid}')
            path.chmod(0o600)
            with self.assertRaises(debug.ConfigurationError) as error:
                debug.read_firebase(root)
            self.assertNotIn('private-fixture', str(error.exception))

    def test_telegram_range_and_firebase_remain_independent(self):
        for value in ['1', '2147483647']:
            environment = {'AMEGRAM_API_ID': value, 'AMEGRAM_API_HASH': 'a' * 32}
            self.assertEqual(debug.telegram_configuration(environment), (int(value), 'a' * 32))
        expected = debug.firebase_resources(firebase_document())
        self.assertEqual(expected['google_api_key'], 'firebase-unit-fixture')
        self.assertNotIn('AMEGRAM_API_HASH', expected)
        self.assertIsNone(debug.telegram_configuration({}, required=False))

    def test_invalid_telegram_values_fail_without_echoing_them(self):
        cases = [{}, {'AMEGRAM_API_ID': '1'}, {'AMEGRAM_API_HASH': 'a' * 32}]
        cases += [{'AMEGRAM_API_ID': value, 'AMEGRAM_API_HASH': 'a' * 32}
                  for value in ['0', '-1', '2147483648', '1;private-value', '1\n']]
        cases += [{'AMEGRAM_API_ID': '1', 'AMEGRAM_API_HASH': 'private-invalid-hash'}]
        for environment in cases:
            with self.subTest(case=cases.index(environment)):
                with self.assertRaises(debug.ConfigurationError) as error:
                    debug.telegram_configuration(environment)
                self.assertNotIn('private-', str(error.exception))
        with self.assertRaises(debug.ConfigurationError):
            debug.telegram_configuration({'AMEGRAM_API_ID': '1'}, required=False)

    def test_wrong_duplicate_incomplete_and_cross_project_firebase_fail(self):
        source = firebase_document()
        wrong = copy.deepcopy(source)
        wrong['client'][0]['client_info']['android_client_info']['package_name'] = 'org.telegram.messenger'
        duplicate = copy.deepcopy(source)
        duplicate['client'] *= 2
        incomplete = copy.deepcopy(source)
        incomplete['client'][0]['api_key'] = []
        cross_project = copy.deepcopy(source)
        cross_project['project_info']['project_number'] = '456'
        for document in [None, {}, wrong, duplicate, incomplete, cross_project]:
            with self.assertRaises(debug.ConfigurationError):
                debug.firebase_resources(document)

    def test_generated_firebase_resources_must_match_without_disclosing_values(self):
        expected = debug.firebase_resources(firebase_document())
        resources = ET.Element('resources')
        for name, value in expected.items():
            ET.SubElement(resources, 'string', name=name).text = value
        debug.verify_firebase_output(expected, resources)
        resources.find('string').text = 'private-mismatched-fixture'
        with self.assertRaises(debug.ConfigurationError) as error:
            debug.verify_firebase_output(expected, resources)
        self.assertNotIn('private-mismatched-fixture', str(error.exception))
        self.assertNotIn('firebase-unit-fixture', str(error.exception))

    def test_merged_identity_provider_and_fcm_boundaries(self):
        debug.verify_manifest(merged_manifest())
        cases = []
        manifest = merged_manifest()
        manifest.set('package', 'org.telegram.messenger')
        cases.append(manifest)
        for attribute, value in [('authorities', 'org.telegram.messenger.firebaseinitprovider'), ('exported', 'true')]:
            manifest = merged_manifest()
            manifest.find('application/provider').set(debug.ANDROID + attribute, value)
            cases.append(manifest)
        manifest = merged_manifest()
        manifest.find('application').append(copy.deepcopy(manifest.find('application/provider')))
        cases.append(manifest)
        manifest = merged_manifest()
        manifest.find('application/service').set(debug.ANDROID + 'exported', 'true')
        cases.append(manifest)
        manifest = merged_manifest()
        manifest.find('application/meta-data').set(debug.ANDROID + 'value', 'false')
        cases.append(manifest)
        manifest = merged_manifest()
        manifest.remove(manifest.find('uses-permission'))
        cases.append(manifest)
        for manifest in cases:
            with self.assertRaises(debug.ConfigurationError):
                debug.verify_manifest(manifest)

    def test_generated_telegram_constants_are_verified_without_outputting_values(self):
        configuration = (123, 'a' * 32)
        text = 'public static final int AMEGRAM_API_ID = 123; public static final String AMEGRAM_API_HASH = "' + 'a' * 32 + '";'
        debug.verify_build_config(text, configuration)
        for wrong in ['', text.replace('123;', '456;'), text.replace('a' * 32, 'b' * 32)]:
            with self.assertRaises(debug.ConfigurationError) as error:
                debug.verify_build_config(wrong, configuration)
            self.assertNotIn('a' * 32, str(error.exception))


if __name__ == '__main__':
    unittest.main()
