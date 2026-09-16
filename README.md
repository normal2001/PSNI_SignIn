# PSNI Sign-In Android App

A simple Kotlin/Android application for local sign-in/sign-out tracking using a local SQLite database and best-effort CSV exports to an SMB fileshare using **SMBJ 0.13.0**.
It is intended to be installed on an Android 16 tablet in kiosk mode (app pinning).

## Installation

The debug build signed installer package is usually named `app-debug.apk`.

1. On the device, download the installer package from a network source or shared with the device via a USB connection.
2. Open the installer package
3. Since the package is not coming from Google Play Store, you may need to select "Allow from this source" in the Download Manager settings.
4. Additional pop-up warnings may need to be accepted to allow installation from untrusted sources.

## CSV exports to SMB fileshare (optional)

SMB requirements:

- SMB2/SMB3-capable server reachable from the Android device.
- TCP 445 reachable from the Android network to the server.
- Configured SMB account has permission to create directories and overwrite the CSV in the configured share/path.
- The app has Android `INTERNET` permission (already declared).

After every successful database **sign-in**, **sign-out**, or **admin edit**, the app queues a background write to a CSV file (`PSNI_Sign-In_Sheet_YYYY.csv`) on an SMB fileshare, if enabled. If not enabled, the records are only visible within the application. The CSV exporter rebuilds the entire current-year CSV and overwrites it. This ensures that any records are not missing if the CSV export option was temporarily disabled or fails to be written. The 'YYYY' in the filename represents the current year, so this file will rollover to a new file name each year.

CSV columns are exactly:

`sign_in_datetime,sign_out_datetime,first_name,last_name,visiting,reason`

`visiting` contains who the visitor is visiting, and `reason` contains the reason
for the visit. Missing values are exported as empty fields.

SQLite is the local source of truth. If SMB is unavailable, authentication fails, or the remote file cannot be written, the local database transaction stays committed and the SMB problem is written to `psni_sign_in_error.txt`.

The Fileshare tab's **Export CSV** button copies the current yearly CSV in the
share root to a timestamped snapshot such as:

`PSNI_Sign-In_Sheet_2026-09-04-134205_export.csv`

The page reports whether that copy succeeded or failed.

## Slack notifications (optional)

Slack requirements:
  - Slack app/bot and token with specific OAuth scoped permissions
  - Slack user group for displaying members in the app and sending individual notifications
  - Slack channel for sending a broadcast notification instead of individual

Create a new Slack app:
  1. https://api.slack.com/apps/, click on "Create New App", then choose "Starter App"
  2. Give the app a name, such as "PSNI Sign-in" or "Sign-in notification"
  3. Select the appropriate Workspace
  4. Click "Review App" and then the "Create and Install"
  5. Under your "Your Apps", locate the "Your App Configuration Tokens" and click on "Generate Token"
  6. Copy/save the token for later usage inside of the PNSI_Sign_in application settings.

Update bot token permissions:
  1. https://api.slack.com/apps, click the new app name to manage the app/bot
  2. Click on "OAuth & Permissions" and scroll down to the "Scopes" section
  3. Confirm the following permissions exist or click "Add an OAuth Scope" as needed:
    `usergroups:read` and `users:read` permissions to refresh the user cache.
    `chat:write` - used for Channel notification delivery 
    `im:write` - used for Individual user notifcation delivery

Create a Slack user group:
  1. https://<workspace>.slack.com/admin/user_groups, click on "Edit User Groups" and then "Create a new group"
  2. Name (display name)
  3. Handle (@<handle> can be used to get the entire group's attention)
  4. Purpose (text description)
  5. Default channels (automatically add user group members to an optional broadcast notification channel)
  6. Click "Create Group"
  7. Click on Edit Group Members for the new group to invite each user to the group.

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

## Audit logging

Successful database transaction types are appended to `psni_sign_in_audit.txt` with timestamps:

- `sign-in`
- `sign-out`
- `admin-edit`

When signing out users from the Admin page using the "Sign Out" button, this is audited as `sign-out` 
because it performs the same transaction type.


## Build/import

1. Clone the github project (https://github.com/normal2001/PSNI_SignIn.git) or extract a downloaded .zip file.
2. Open the extracted `PSNI_SignIn` folder in Android Studio.
3. Let Android Studio sync Gradle and download dependencies from Google Maven/Maven Central.
4. Install Android SDK 36 if Android Studio requests it.
5. Use JDK 17.
6. Set the SMB values in `AppConfig_test.properties` or from the Fileshare admin tab before testing SMB export.
7. Build and run on Android 8.0 (API 26) or newer.

The project specifies Android Gradle Plugin 8.13.2, Kotlin 2.3.21, Gradle 8.13, compile/target SDK 36, and SMBJ 0.13.0.

> The standard `gradle-wrapper.jar` binary is intentionally not included in this generated source archive. Android Studio can import/sync the project directly. If you want command-line `./gradlew` usage, generate/add the wrapper from Android Studio or a local Gradle installation.

## Important configuration

Deployment defaults are defined in `app/build.gradle.kts` and exposed through
`app/src/main/java/com/example/psnisignin/AppConfig.kt`.

### Debugging using local test properties:

1. Edit `AppConfig_test.properties` in the project root. On a fresh clone, copy
   `AppConfig_test.example.properties` to that name first.
2. Use the exact keys shown in the example, with one `KEY=value` per line. All
   administrator settings, the reason choices, and `useMockData` are supported.
3. Sync Gradle and rebuild/run the **debug** variant after making changes.

This `AppConfig_test.properties` file is ignored by Git so your custom settings
remain local only.

Debug setting precedence: 
  1. any settings saved through the admin screen
  2. the `AppConfig_test.properties` settings
  3. the app defaults. 

The `AppConfig_test.properties` settings work as initial values for debugging, but 
any changes saved inside the app take priority and survive app restarts and normal
rebuilds/reinstalls that retain app data. The `REASON_OPTIONS` and `useMockData` are
build settings without saved admin overrides. Changing local properties requires
rebuilding and installing the app; restarting the emulator app alone does not update
it or erase saved settings. 

Release builds ignore the local properties file entirely. Release builds and debug
builds without local overrides use empty Slack connection credentials and disabled
SMB fileshare exports by default. The default admin passcode remains `1234`.
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

If the SQLite database file cannot be opened at startup, the home page displays a
warning and disables check-in/check-out. The Settings button and admin passcode 
remain usable; login opens Application settings directly. Visitor records and CSV
export are unavailable until database access is restored. Save a corrected 
`DATABASE_PATH` to retry immediately. Failed attempts keep settings accessible. A 
successful retry opens or creates the database and re-enables check-in/check-out
when you return Home. Existing records are not copied from the old database. Changing
the path while a working database is already open still requires a full app restart.

Android does not provide a dependable writable process working directory. The app
interprets relative paths such as `./psni_sign_in_audit.txt`, `./psni_sign_in_error.txt`,
and `./psni_sign_in.db` relative to the app's private internal files directory. Absolute
paths are accepted by the resolver, but Android storage/scoped-storage permissions
still apply.

In the Admin screen's Application tab, the **View** button beside `AUDIT_LOG_PATH` or
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

## Security notes

This project follows the requested source-level configurable passcode and SMB credentials. For a production deployment, hard-coded secrets are not strong secret storage: APK contents can be inspected. Prefer managed configuration and Android Keystore-backed secret storage if the application will be distributed beyond a controlled device fleet.

The 4-digit passcode is a convenience gate, not strong authentication. Admin Activities are marked `android:exported="false"`, reducing direct launch exposure from other apps.
