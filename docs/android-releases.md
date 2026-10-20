# Android releases and updates

GitHub Actions builds the Android app. Pushes to main and pull requests run Android debug compilation, unit tests, lint and instrumented-test compilation, plus backend regression tests. CI does not connect to the household router and does not run the opt-in real-backend phone test. Backend binaries and server deployments are not published by the release workflow.

## Publish a version

1. Increase versionCode and versionName in android/app/build.gradle.kts. Version codes must increase for the in-app updater to offer an upgrade.
2. Add docs/releases/VERSION.md with user-facing release notes.
3. Push the reviewed commit to main and wait for Checks to pass.
4. Tag that commit vMAJOR.MINOR.PATCH and push the tag. Android release validates that the tag belongs to main, runs tests/lint, builds a signed non-debuggable APK and verifies its signature. It creates a draft, uploads the APK, SHA256SUMS and update.json, then publishes the complete release.

The release tag must match the versionName embedded in the actual Gradle APK metadata. scripts/prepare_android_release.py creates updater metadata from that APK, rather than guessing from the tag. Only a single universal Android APK is released. Workflow actions are pinned to commit SHAs; signing material is restored under the runner's temporary directory and removed afterward.

## Signing

Repository Actions secrets: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD. Never put these values in source, logs, workflow arguments or issue comments.

For this household's first GitHub release, the certificate/key used by the already-installed app is retained so updates preserve app data and encrypted backend setup. It has been rewrapped into a separate password-protected PKCS12 keystore; the release build itself is not debuggable. This is a GitHub sideload distribution, not a Google Play signing setup. A new unrelated key would prevent upgrading the existing installation.

The development computer's excluded, owner-only secrets directory contains android-release.p12 and android-signing.json. Back up these signing files securely outside the computer. GitHub Actions secrets are not a recoverable key backup. Losing the signing key prevents future compatible updates.

## In-app updater

The app checks this repository's latest stable GitHub release on startup/foreground, at most once every six hours after a successful check. Manual checks are under Settings → Advanced settings → Diagnostics. Attempts are rate-limited to one per minute. No release yet, no Internet or a failed check leaves the rest of the app usable.

A notice appears above the app header, below the system status bar, pushing both the header and page content down. Update downloads the APK; Install opens Android's installation flow. Android may first ask the user to allow installation from Home WiFi. After granting permission, return to the app and press Install again. Installation always needs Android/user approval.

The updater uses a separate unauthenticated HTTPS client: the home-server bearer token and router/AdGuard credentials never go to GitHub. It accepts fixed repository release paths and known GitHub HTTPS/CDN hosts only. Before offering installation, it checks the declared size, SHA-256, Android package ID, version code/name, minimum Android version and the installed app's signing certificate. Metadata reads and APK size are bounded. Partial/failed downloads are removed; completed downloads are private app files, rechecked on reuse and shared only through a narrowly scoped FileProvider.

Dismissing a real notice hides that version until a manual update check; newer releases remain eligible. Debug builds show Preview update banner at the top of Settings. Its Update button is inert apart from a preview message. The preview option is absent from release UI and cannot download or install anything.

Official references: [GitHub Releases API](https://docs.github.com/en/rest/releases/releases), [Android app signing](https://developer.android.com/studio/publish/app-signing), [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).
