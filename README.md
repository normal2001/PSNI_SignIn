# PSNI Sign-In Android App

A deliberately simple Kotlin/Android application for local sign-in/sign-out tracking with SQLite and best-effort yearly CSV export over SMB using **SMBJ 0.13.0**.

## Important configuration

Deployment defaults are defined in `app/build.gradle.kts` and exposed through
`app/src/main/java/com/example/psnisignin/AppConfig.kt`.

### Personal debug settings

1. Edit `AppConfig_test.properties` in the project root. On a fresh clone, copy
   `AppConfig_test.example.properties` to that name first.
2. Use the exact keys shown in the example, with one `KEY=value` per line. All
   administrator settings, the reason choices, and `useMockData` are supported.
3. Sync Gradle and rebuild/run the **debug** variant after making changes.

The personal file is ignored by Git. Commit the example, Gradle configuration,
and `AppConfig.kt`. The legacy extensionless `AppConfig_test` file is also ignored
but is not read by the build.

Debug setting precedence is: the setting saved through the admin screen, then a
local property, then the shared default. Local properties supply starting values;
changes saved inside the app take priority and survive app restarts and normal
rebuilds/reinstalls that retain app data. Previously saved settings also take
priority, including settings saved before this change. Changing a local property
requires rebuilding and installing the app; restarting the emulator app alone
does not update it or erase saved settings. Reason choices and `useMockData` are
build settings without saved admin overrides.

Release builds ignore the local properties file entirely. Release builds and debug
builds without local overrides use empty connection credentials and disabled SMB
export/Slack notifications by default. The default admin passcode remains `1234`.
Previously saved settings take priority in both debug and release builds.

Properties are read as UTF-8. Do not surround values with quotes. Use `\\` for a
literal backslash and `\n` for a newline. Separate `REASON_OPTIONS` with `|`.
`SLACK_NOTIFICATION_TARGET` must be `CHANNEL` or `INDIVIDUALS`; these identifiers
and the internal preference-store name are not customizable settings. Booleans must
be `true` or `false`; unknown keys or invalid values cause a debug build error.
`useMockData` only exposes a flag; the app currently has no mock-data implementation.

Local credentials are excluded from source control, but are included in your debug
APK. If credentials previously committed to Git were real, rotate them; ignoring a
file does not remove credentials from earlier commits.

### Android local-path behavior

If the database cannot be opened at startup, the home page displays a warning and
disables check-in/check-out. The Settings button and admin passcode remain usable;
login opens Application settings directly. Visitor records and CSV export are
unavailable until database access is restored. Save a corrected `DATABASE_PATH`
to retry immediately. Failed attempts keep settings accessible. A successful retry
opens or creates the database and re-enables check-in/check-out when you return Home.
Existing records are not copied from the old database. Changing the path while a
working database is already open still requires a full app restart.

Android does not provide a dependable writable process working directory. Therefore this project interprets relative paths such as `./audit.log`, `./error.log`, and `./psni_sign_in.db` relative to the app's private internal files directory. Absolute paths are accepted by the resolver, but Android storage/scoped-storage permissions still apply.

In the Admin screen's Application tab, **View** beside `AUDIT_LOG_PATH` or
`ERROR_LOG_PATH` opens that log in the default text-viewing app (or Android's app
picker when no default is selected). Save edited paths first. The viewer receives
temporary read-only access to the selected log. Missing or unreadable logs and a
missing text viewer are reported in the tab.

## Data model

SQLite table: `sign_in_records`

- `id` - auto-incrementing primary key
- `first_name`
- `last_name`
- `sign_in_datetime`
- `sign_out_datetime` - NULL while the person remains signed in

Timestamp format: `yyyy-MM-dd HH:mm:ss`

## CSV behavior

After every successful database **sign-in**, **sign-out**, or **admin edit**, the app queues a background export. The exporter rebuilds the complete current-year CSV and overwrites:

`PSNI_Sign-In_Sheet_YYYY.csv`

CSV columns are exactly:

`sign_in_datetime,sign_out_datetime,first_name,last_name`

SQLite is the source of truth. If SMB is unavailable, authentication fails, or the remote file cannot be written, the local transaction stays committed and the SMB problem is written to `error.log`.

The Fileshare tab's **Export CSV** button copies the current yearly CSV in the
share root to a timestamped snapshot such as:

`PSNI_Sign-In_Sheet_2026-09-04-134205_export.csv`

The page reports whether that copy succeeded or failed.

## Slack notification behavior

At application startup, the configured Slack user group is queried in the
background and its user IDs and display names are cached in the local SQLite
`slack_user_cache` table. A failed refresh retains the last successful cache.
The cached names populate the "Who are you visiting?" dropdown along with an
`Other` option.

When Slack notifications are enabled, each successful sign-in either posts once
to the configured channel (the default delivery mode) or sends a direct message
to the cached Slack user selected in the "Who are you visiting?" dropdown. The
`Other` option has no Slack user ID, so individual delivery is skipped and logged
for that selection. Slack runs in the background; lookup or delivery failures are
written to the error log and never roll back a successful local sign-in.

The Slack bot token needs `usergroups:read` and `users:read` to refresh the user
cache. Channel delivery needs `chat:write`; individual delivery additionally
needs `im:write`.

## Audit behavior

Successful database transaction types are appended to `audit.log` with timestamps:

- `sign-in`
- `sign-out`
- `admin-edit`

The admin list's Sign Out button is audited as `sign-out` because it performs the same transaction type.

## Build/import

1. Extract the ZIP.
2. Open the extracted `PSNI_SignIn` folder in Android Studio.
3. Let Android Studio sync Gradle and download dependencies from Google Maven/Maven Central.
4. Install Android SDK 36 if Android Studio requests it.
5. Use JDK 17.
6. Set the SMB values in `AppConfig_test.properties` or from the Fileshare admin tab before testing SMB export.
7. Build and run on Android 8.0 (API 26) or newer.

The project specifies Android Gradle Plugin 8.13.2, Kotlin 2.3.21, Gradle 8.13, compile/target SDK 36, and SMBJ 0.13.0.

> The standard `gradle-wrapper.jar` binary is intentionally not included in this generated source archive. Android Studio can import/sync the project directly. If you want command-line `./gradlew` usage, generate/add the wrapper from Android Studio or a local Gradle installation.

## SMB requirements

- SMB2/SMB3-capable server reachable from the Android device.
- TCP 445 reachable from the Android network to the server.
- Configured SMB account has permission to create directories and overwrite the CSV in the configured share/path.
- The app has Android `INTERNET` permission (already declared).

## Security notes

This project follows the requested source-level configurable passcode and SMB credentials. For a production deployment, hard-coded secrets are not strong secret storage: APK contents can be inspected. Prefer managed configuration and Android Keystore-backed secret storage if the application will be distributed beyond a controlled device fleet.

The 4-digit passcode is a convenience gate, not strong authentication. Admin Activities are marked `android:exported="false"`, reducing direct launch exposure from other apps.
