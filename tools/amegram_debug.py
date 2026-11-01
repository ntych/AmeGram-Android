#!/usr/bin/env python3
"""Validate independent debug registrations, then optionally build a real APK.

The helper accepts no credential arguments, writes no secret export files and
prints no values. Gradle's local outputs necessarily contain client registration
values. Firebase checks do not authenticate or claim delivery/login works.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.umi.amegram.beta'
FIREBASE_FILE = Path('TMessagesProj_App/src/debug/google-services.json')
ANDROID = '{http://schemas.android.com/apk/res/android}'


class ConfigurationError(Exception):
    """Only fixed, non-sensitive messages may be attached to this exception."""


class RedactedArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.print_usage(sys.stderr)
        self.exit(2, 'Invalid command arguments. Supply credentials only through the environment; argument values omitted.\n')


def telegram_configuration(environment, required=True):
    api_id = environment.get('AMEGRAM_API_ID', '')
    api_hash = environment.get('AMEGRAM_API_HASH', '')
    if not api_id and not api_hash and not required:
        return None
    if not api_id or not api_hash:
        raise ConfigurationError('Set both AMEGRAM_API_ID and AMEGRAM_API_HASH in the build process environment.')
    if (not re.fullmatch(r'[0-9]{1,10}', api_id) or not 0 < int(api_id) <= 2147483647
            or not re.fullmatch(r'[a-fA-F0-9]{32}', api_hash)):
        raise ConfigurationError('Invalid Telegram API configuration; values intentionally omitted.')
    return int(api_id), api_hash


def firebase_resources(document):
    """Select the real debug client, independently of Telegram API values."""
    try:
        clients = [client for client in document['client']
                   if client['client_info']['android_client_info']['package_name'] == PACKAGE]
        if len(clients) != 1:
            raise ValueError()
        client = clients[0]
        project = document['project_info']
        resources = {
            'google_app_id': client['client_info']['mobilesdk_app_id'],
            'google_api_key': client['api_key'][0]['current_key'],
            'gcm_defaultSenderId': project['project_number'],
            'project_id': project['project_id'],
        }
        if any(not isinstance(value, str) or not value.strip() for value in resources.values()):
            raise ValueError()
        if not re.fullmatch(r'[0-9]+', resources['gcm_defaultSenderId']):
            raise ValueError()
        if not re.fullmatch(r'1:' + resources['gcm_defaultSenderId'] + r':android:[a-fA-F0-9]+',
                            resources['google_app_id']):
            raise ValueError()
        return resources
    except (KeyError, IndexError, TypeError, ValueError):
        raise ConfigurationError('Firebase JSON needs exactly one complete dev.umi.amegram.beta client in its matching project.') from None


def read_firebase(root):
    path = root / FIREBASE_FILE
    try:
        if path.stat().st_size > 2 * 1024 * 1024:
            raise ConfigurationError('Firebase configuration exceeds the expected size.')
        if os.name == 'posix' and path.stat().st_mode & 0o077:
            raise ConfigurationError('Restrict the local debug Firebase file to owner access (chmod 600).')
        return firebase_resources(json.loads(path.read_text()))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise ConfigurationError('Supply a readable, valid local TMessagesProj_App/src/debug/google-services.json; contents omitted.') from None


def verify_firebase_output(expected, resources):
    generated = {element.get('name'): element.text for element in resources.findall('string')}
    if any(generated.get(name) != value for name, value in expected.items()):
        raise ConfigurationError('Generated Firebase resources differ from the debug JSON. Check higher-priority variant files/overrides; values omitted.')


def verify_manifest(manifest):
    if manifest.get('package') != PACKAGE:
        raise ConfigurationError('Merged manifest does not use the independent debug package.')
    application = manifest.find('application')
    if application is None:
        raise ConfigurationError('Merged manifest is missing its application.')
    authorities = set()
    firebase_providers = []
    for provider in application.findall('provider'):
        for authority in provider.get(ANDROID + 'authorities', '').split(';'):
            if not authority.startswith(PACKAGE + '.') or authority in authorities:
                raise ConfigurationError('Merged provider authorities collide or use another application identity.')
            authorities.add(authority)
        if provider.get(ANDROID + 'name') == 'com.google.firebase.provider.FirebaseInitProvider':
            firebase_providers.append(provider)
    if (len(firebase_providers) != 1
            or firebase_providers[0].get(ANDROID + 'authorities') != PACKAGE + '.firebaseinitprovider'
            or firebase_providers[0].get(ANDROID + 'exported') != 'false'):
        raise ConfigurationError('Firebase initialization provider must be unique, private and scoped to AmeGram debug.')
    services = [element for element in application.findall('service')
                if element.get(ANDROID + 'name') == 'org.telegram.messenger.GcmPushListenerService']
    if (len(services) != 1 or services[0].get(ANDROID + 'exported') != 'false'
            or not any(action.get(ANDROID + 'name') == 'com.google.firebase.MESSAGING_EVENT'
                       for action in services[0].findall('intent-filter/action'))):
        raise ConfigurationError('AmeGram FCM service must be unique, private and handle MESSAGING_EVENT.')
    metadata = {element.get(ANDROID + 'name'): element.get(ANDROID + 'value')
                for element in application.findall('meta-data')}
    if (metadata.get('firebase_analytics_collection_deactivated') != 'true'
            or metadata.get('google_analytics_adid_collection_enabled') != 'false'):
        raise ConfigurationError('Debug analytics/advertising collection opt-outs must remain enabled.')
    if metadata.get('com.google.android.maps.v2.API_KEY') != '@string/amegram_google_maps_api_key':
        raise ConfigurationError('Debug Maps configuration must use its separate optional owner resource.')
    permissions = {element.get(ANDROID + 'name') for element in manifest.findall('uses-permission')}
    if not {'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS'} <= permissions:
        raise ConfigurationError('Merged debug manifest needs Internet and notification permission declarations.')


def verify_build_config(text, telegram):
    api_id = re.search(r'\bAMEGRAM_API_ID\s*=\s*([0-9]+)\s*;', text)
    api_hash = re.search(r'\bAMEGRAM_API_HASH\s*=\s*"([a-fA-F0-9]*)"\s*;', text)
    if (not api_id or not api_hash
            or (int(api_id.group(1)), api_hash.group(1)) != telegram):
        raise ConfigurationError('Generated Telegram BuildConfig does not match the process environment; values omitted.')


def gradle(root, *tasks, abi='x86_64'):
    # No credential arguments, daemon reuse, configuration serialization, or build-cache uploads.
    command = [str(root / 'gradlew'), '--no-daemon', '--no-parallel', '--max-workers=1',
               '--no-build-cache', '--no-configuration-cache', '--console=plain',
               '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g',
               '-PamegramSourceChecks=false', '-Pandroid.injected.build.abi=' + abi, *tasks]
    subprocess.run(command, cwd=root, check=True)


def main(arguments=None):
    parser = RedactedArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['manifest-check', 'firebase-check', 'check', 'build'])
    parser.add_argument('--abi', choices=['x86_64', 'arm64-v8a', 'armeabi-v7a', 'x86'], default='x86_64')
    args = parser.parse_args(arguments)
    try:
        if args.mode == 'manifest-check':
            verify_manifest(ET.parse(ROOT / 'TMessagesProj_App/build/intermediates/merged_manifest/afatDebug/processAfatDebugMainManifest/AndroidManifest.xml').getroot())
            print('PASS: merged debug identity, private Firebase provider/service and analytics/Maps boundaries.')
            return 0
        telegram = telegram_configuration(os.environ, required=args.mode != 'firebase-check')
        expected = read_firebase(ROOT)
        subprocess.run([sys.executable, str(ROOT / 'tools/check_build_contract.py')], cwd=ROOT, check=True)
        gradle(ROOT, 'checkAmeGramApplicationIds', ':TMessagesProj_App:processAfatDebugResources',
               ':TMessagesProj_App:processAfatDebugMainManifest', ':TMessagesProj_App:checkAfatDebugDuplicateClasses',
               ':TMessagesProj_App:compileAfatDebugJavaWithJavac', abi=args.abi)
        output = ROOT / 'TMessagesProj_App/build'
        verify_firebase_output(expected, ET.parse(output / 'generated/res/google-services/afat/debug/values/values.xml').getroot())
        verify_manifest(ET.parse(output / 'intermediates/merged_manifest/afatDebug/processAfatDebugMainManifest/AndroidManifest.xml').getroot())
        if telegram:
            verify_build_config((ROOT / 'TMessagesProj/build/generated/source/buildConfig/debug/org/telegram/messenger/BuildConfig.java').read_text(), telegram)
        print('PASS: debug Firebase generation, merged identity/provider/FCM boundaries, duplicate classes and Java/resources.')
        if args.mode == 'firebase-check':
            print('Firebase build checks only; no Telegram readiness, APK or push-delivery result claimed.')
        else:
            print('PASS: Telegram API environment and generated BuildConfig match; no credentials disclosed.')
        print('Optional Google Maps registration: ' + ('supplied (service access unverified).' if os.environ.get('AMEGRAM_GOOGLE_MAPS_API_KEY') else 'not supplied; Maps rendering remains unconfigured.'))
        if args.mode == 'build':
            gradle(ROOT, ':TMessagesProj_App:assembleAfatDebug', abi=args.abi)
    except ConfigurationError as error:
        print('BLOCKED:', error, file=sys.stderr)
        return 1
    except (OSError, UnicodeError, ET.ParseError):
        print('BLOCKED: build output/configuration unavailable or malformed; private details omitted.', file=sys.stderr)
        return 1
    except subprocess.CalledProcessError:
        print('FAILED: build/configuration check; inspect the preceding task diagnostics.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
