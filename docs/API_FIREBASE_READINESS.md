# Telegram API and Firebase debug readiness — 2026-10-17

Baseline: `e55bec595c`, after the owner merged the foundation/build-audit PRs. The readiness work does not merge changes, modify application IDs or production signing/credential declarations, or change the AmeGram interface. See [BUILDING.md](BUILDING.md) for executable commands and cloud secret handoff.

## Registration boundaries

| Registration | Source | Destination |
| --- | --- | --- |
| Telegram application ID / hash | `AMEGRAM_API_ID` / `AMEGRAM_API_HASH` in the Gradle process environment | Library `BuildConfig.AMEGRAM_API_ID` / `AMEGRAM_API_HASH`, then upstream `BuildVars.APP_ID` / `APP_HASH` |
| Firebase Android application | Ignored `TMessagesProj_App/src/debug/google-services.json` for `dev.umi.amegram.beta` | Google-services plugin in the regular app generates Firebase string resources |
| Google Maps SDK for Android (optional) | `AMEGRAM_GOOGLE_MAPS_API_KEY` | Separate `amegram_google_maps_api_key` resource referenced by debug manifest metadata |

Telegram credentials are never substituted into Firebase JSON or Google resources. Firebase's sender/project number must match its mobile SDK application ID; it has no numerical relationship to Telegram's API ID. Firebase registration alone cannot supply Telegram API access or Google Maps authorization.

The active cloud runtime reported no configured secret bindings at audit time. The helper requires API variables in its actual process environment, not merely saved in a settings UI. Setup-only secret availability applies to particular Codex Cloud setups; other managed environments may support runtime secret bindings. No persistent export file is created to work around that lifecycle.

## Corrections and automation

- Added `tools/amegram_debug.py`: `firebase-check` runs real local Firebase build checks without requiring Telegram configuration; `check` also requires/validates owner API values and compares generated BuildConfig; `build` runs those checks before normal debug APK assembly. `manifest-check` validates the source-CI merged manifest without accessing owner JSON/API values.
- The helper takes no credential arguments, does not print values/parser excerpts, and does not write secret export files. Even rejected CLI arguments are redacted. Owner builds use no build/configuration cache or reusable Gradle daemon. Generated client constants/resources/APKs inherently contain registration values and remain private ignored local outputs.
- Corrected both debug manifests: the inherited FCM service was exported. It now uses `exported=false`, matching Firebase's supported service declaration; Firebase's receiver still dispatches within the application. The merged private initialization provider and independent authority are checked too.
- The earlier BuildVars cleanup did not cover inherited Maps keys in debug manifests. Replaced those literal keys with a separate optional owner resource. No Firebase key or Telegram hash is reused for Maps. Without the optional Maps registration, rendering remains unconfigured.
- Extended PR source CI with real app Java, merged-manifest and duplicate-class checks plus the manifest contract. Owner Firebase/API configuration is still absent from PR jobs and artifacts. Existing app/runtime dependency versions are retained.
- Added synthetic parser/output/manifest regression tests. They exercise missing/invalid API values, signed-int bounds, wrong/duplicate/cross-project Firebase clients, stale generated values, provider collisions/exported services, analytics opt-outs, redacted errors and early build refusal. Synthetic values never build an APK or contact a service.

## Checks performed

| Check | Result / scope |
| --- | --- |
| `python3 tools/amegram_debug.py firebase-check` with owner's existing JSON | **PASS**: 211 Gradle tasks, 209 executed / 2 up-to-date, 2m10s; normal mode, no build cache |
| Library + regular app Java, resource linking, AAR metadata | **PASS** in the same invocation; upstream Java 8 / Gradle deprecation and resource warnings remain |
| Firebase dependency insight | **PASS**: runtime graph resolves; messaging 22.0.0, common 20.0.0, installations 17.0.0 and components 17.0.0 selected. Older transitive component/encoder/transport requests resolve to single versions; declarations were not upgraded |
| `checkAfatDebugDuplicateClasses` | **PASS** on the actual debug runtime classpath |
| Actual Google-services output | **PASS**: generated mobile app ID, Google API key, sender ID and project ID match the selected debug JSON without printing values |
| Merged identity / Firebase / FCM / analytics / Maps | **PASS**: debug package, unique private FirebaseInitProvider with debug authority, private messaging service/action, Internet/notification permissions, analytics opt-outs and separate Maps resource |
| Actual AGP IDs | **PASS**: debug `dev.umi.amegram.beta`, release/bundle release `dev.umi.amegram` |
| Python regression + repository contract | **PASS**: 12 tests and committed-file/ignore/debug-manifest checks |
| Workflow static analysis | **PASS**: actionlint 1.7.7; GitHub-hosted source checks and reports are linked from the follow-up PR |
| `python3 tools/amegram_debug.py check` without API bindings | **EXPECTED BLOCK** before Gradle; asks for both variable names without printing values |
| Owner JSON | Kept in place, ignored/untracked, mode 0600; never included in a commit/artifact or printed |
| Production inputs | Release manifests/credentials, signing fixture and gradle.properties unchanged against the baseline |

These results establish build-time configuration boundaries, compilation and absence of duplicate classes, not the absence of every possible service/runtime incompatibility.

## Remaining gates

1. Add the owner's Telegram API ID/hash through supported secret bindings available to the build process, then run `python3 tools/amegram_debug.py check` and `build --abi x86_64` (or the physical device's ABI). Actual owner-valued BuildConfig comparison cannot be verified before the values exist. No configured APK, login or messaging result is claimed.
2. Use an accelerated emulator or physical Android device for login, notification permission, FCM registration/delivery and settings persistence tests. The installed default/AOSP image lacks Google Play Services, and the prior software-emulator run had system crashes; it is not a stable push-test target.
3. Confirm Firebase/Maps service enablement, API restrictions and debug signing fingerprints in the owner's Google project. Local JSON/resource matching does not validate server-side permissions. No Google OAuth/reCAPTCHA/SMS integration is automatically enabled by adding Telegram credentials.
4. Full native/ABI/16 KiB runtime validation and full lint triage remain separate. The last full lint report is historical (348 errors / 6163 warnings), not a fresh result from this change.
5. Distribution remains blocked. Release/standalone manifests still contain upstream Maps fixture declarations and need a separate credential/service audit before enabling distribution. They were not altered under the instruction to preserve release credentials/signing.
