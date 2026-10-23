# AmeBackup: local interface settings

AmeBackup is implemented in AmeGram Settings without Telegram API access. This first version exports, previews/imports and resets the **OLED background preference for AmeGram Settings only**. It is not a Telegram account, chat, theme, media or session backup. The long-term feature catalog is still a backlog.

## User flow

- Export opens Android's `ACTION_CREATE_DOCUMENT` picker with a JSON filename. The user selects the destination and provider; AmeGram has no backup server or automatic upload. A chosen document provider may itself store files in the cloud.
- Import uses `ACTION_OPEN_DOCUMENT`. The entire file is validated before showing a localized OLED on/off preview. Preferences change only after **Apply settings**. Canceling selection or confirmation performs no setting write.
- Reset asks for confirmation, then restores the OLED default (off). Only the known AmeGram preference is written; unrelated keys and all upstream Telegram settings are preserved.
- Document I/O and synchronous preference commits run on a dedicated background thread rather than the UI or Telegram's shared queues. Controls are disabled during an active operation. Failures are visible and are never reported as success.
- New dialogs explicitly disable Telegram's optional blurred background and use solid AmeGram surfaces/purple actions. Rows keep 56 dp minimum touch targets and button accessibility semantics; status text uses a polite accessibility live region. No new custom animation is introduced.

The feature adds no storage permission, persistent URI grant, account access, dependency, analytics or network client. Only a system-picker-selected `content://` URI is opened. A selected operation may finish after navigating away; results wait until the settings fragment resumes, and callbacks skip a destroyed fragment. No document URI, file contents or parser/provider exception is logged.

## Format and storage boundary

UTF-8 JSON, at most **16 KiB**:

```json
{
  "format": "amegram-settings",
  "version": 1,
  "settings": {
    "oled_dark_background": true
  }
}
```

`AmeGramBackup` uses the existing Gson streaming reader in strict mode. It rejects unknown or duplicate fields, unsupported/coerced versions, missing settings, incorrect types, trailing documents, malformed UTF-8 and oversized input. There is no generic preference-map import or reflection. Future settings require an explicit schema review; unknown newer formats are refused rather than partially applied.

`AmeGramSettings.snapshot()` reads only the allowlisted getter; it never enumerates `SharedPreferences.getAll()`. Restore uses one editor/commit in `amegram_settings` and never clears the preference file or opens upstream preference stores. Android commit failure is surfaced. As with Android SharedPreferences generally, failed disk writes may already have affected the in-memory value; the UI refreshes current state and does not claim durable success or rollback.

The exported file is plain interface configuration, not encrypted private-data storage. Telegram API values, Firebase/Maps configuration, authentication/session keys, chat content and future unknown preference values cannot be exported by this schema.

## Verification — 2026-10-23

- **PASS locally:** full API-independent Gradle source/resource/unit command, 227 tasks (6 executed, 221 up-to-date), 31s after the dialog refinement. The initial broader pass executed 40 tasks in 1m38s. Library/app Java and resources, actual application IDs, merged manifests and duplicate classes passed. No installable API-less APK was packaged.
- **PASS locally after the lifecycle refinement:** Java compilation and JVM tests, 154 tasks (5 executed, 149 up-to-date), 29s. Paused fragments defer result presentation until resume.
- **PASS:** 15 JVM tests total, 0 failures/errors (actual XML reports inspected). 10 codec tests cover round trips, field order, allowlists, duplicates, schema versions/types, malformed syntax/UTF-8, trailing data, size boundaries and redacted I/O failures.
- 3 storage-boundary JVM tests use an explicit preference double to verify allowlisted export, restore/reset preserving unrelated data, synchronous writes and failure reporting. They do **not** emulate actual Android disk/process behavior.
- The existing 2 palette contrast/OLED JVM tests passed unchanged. The 12 configuration Python tests, repository/merged-manifest contracts, actionlint 1.7.7, EN/RU resource-key checks and documentation links also passed.
- A real Android preference/backup round-trip instrumentation test was added to the existing sandboxed suite. **Not executed**: the configured APK/device gate remains deferred while development continues without Telegram API credentials.
- Full UI/file-provider interaction, cancellation, process death, TalkBack, theme switching while a dialog is open and device persistence need a future Android run. Source/codec/unit verification is not a claim that these device paths have been exercised.

Exact local and GitHub-hosted run results are recorded in the continuation PR. The prior full lint/native/device limitations in [VERIFICATION.md](VERIFICATION.md) and [BUILDING.md](BUILDING.md) remain; this feature does not alter authentication, MTProto, Firebase, application IDs, signing or the release gate.
