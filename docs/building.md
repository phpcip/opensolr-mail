# Building from source

## Toolchain

| | Version |
|---|---|
| JDK | 17 or newer |
| Android SDK platform | 36 |
| Gradle | wrapper included |
| Minimum Android | 8.0, API 26 |

Point Gradle at the SDK with `ANDROID_HOME`, or a `local.properties` file containing
`sdk.dir=/path/to/Android/sdk`.

## Debug build

```bash
./gradlew assembleGithubDebug
```

The APK is in `app/build/outputs/apk/github/debug/`. A debug build is signed with your local debug key,
which opensolr.com's `assetlinks.json` does not list, so the Opensolr sign-in finishes through the
`opensolr-mail://auth` fallback instead of the verified link.

## Push and Firebase

Instant push reaches the phone through Firebase Cloud Messaging. Official builds carry the Opensolr
`app/google-services.json`, which is not in this repository. To build with push, create a Firebase project,
add an Android app with the package `com.opensolr.mail`, and put its `google-services.json` in `app/`.
Without the file the app builds and runs normally, with push off: new mail is then checked every 15 minutes.

## Release build

Create `signing.properties` in the project root (it is git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

```bash
./gradlew assembleGithubRelease      # the APK for GitHub and sideloading
./gradlew bundlePlayRelease          # the app bundle for Google Play
```

The APK is in `app/build/outputs/apk/github/release/app-github-release.apk`, the bundle in
`app/build/outputs/bundle/playRelease/app-play-release.aab`. Without `signing.properties` they are built
unsigned.

There are two flavors, `github` and `play`, with the same code and the same version. The `github` build
updates itself from the latest GitHub release; the `play` build leaves updating to Google Play and does not
declare `REQUEST_INSTALL_PACKAGES` (`app/src/play/AndroidManifest.xml`).

## Publishing a release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build and check the signature:
   ```bash
   ./gradlew assembleGithubRelease bundlePlayRelease
   apksigner verify --print-certs app/build/outputs/apk/github/release/app-github-release.apk
   ```
3. Commit, tag and push:
   ```bash
   git commit -am "Release X.Y.Z" && git tag vX.Y.Z && git push origin main vX.Y.Z
   ```
4. Publish the release with the APK under its fixed name, so the permanent link keeps working:
   ```bash
   cp app/build/outputs/apk/github/release/app-github-release.apk /tmp/opensolr-mail.apk
   gh release create vX.Y.Z /tmp/opensolr-mail.apk --title "X.Y.Z" --notes "What changed"
   ```
   Every installed copy finds it within a day, or at once with **Check for updates**:
   `https://github.com/phpcip/opensolr-mail/releases/latest/download/opensolr-mail.apk`

## The Solr configuration

`solr/conf` is zipped into the APK's assets by the `solrConfigZip` Gradle task on every build. Change the
schema there, and raise `config_version` in `solrconfig.xml` together with `MailIndex.CONFIG_VERSION`: an
existing index on an older version is reset, configured again and fully reindexed.
