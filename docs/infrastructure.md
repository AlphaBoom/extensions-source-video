# Extension infrastructure

This repository builds **anime/video extensions** for Aniyomi. Keiyoushi is the
reference for shared build infrastructure; its manga API and host runtime are not
interchangeable with Aniyomi's video API.

## Upstream snapshots

Reviewed on 2026-10-13:

- [Keiyoushi](https://github.com/keiyoushi/extensions-source/tree/b5d2cd5c3d23a3408c7daa3eb807668aedfce3e0): Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.10, SDK 37, included build logic, explicit formatting and source metadata.
- [Yuzono](https://github.com/yuzono/tachiyomi-extensions/tree/33f39638b967b40015424a86f6a04cacd7bd06d4): the same shared build baseline.
- [Yuzono anime extensions](https://github.com/yuzono/anime-extensions/tree/8bbb8180517b50327ea5965c4629fe15571cbc74): included build logic and Kotlin DSL, with a Komikku-specific API/runtime fork. Its Injekt and serialization pins are specific to that host and are not copied into official Aniyomi API 17 extensions.
- [Aniyomi extensions-lib v17](https://github.com/aniyomiorg/extensions-lib/tree/971ce96e76202bb35432fe71f92553e3ae40d01d): the authority for video interfaces, manifest metadata and runtime dependencies.
- [Aniyomi host](https://github.com/aniyomiorg/aniyomi/tree/97414446b8a95994c72dd33c41c971a89d4d25b8): API 17 loading and playback behavior.

The manga-only KSP annotations and JAR distribution are not copied: the current
Aniyomi host consumes APK repositories. API stubs and host-provided dependencies
remain `compileOnly`. Coroutines 1.10.2, serialization 1.9.0, OkHttp 5.4.0,
jsoup 1.22.2, Mihon's Injekt and Cash QuickJS match Aniyomi's runtime rather than
the different versions in the manga host.

## Build

Install JDK 25 and Android SDK packages `platforms;android-37.0` and
`build-tools;37.0.0`. Set `ANDROID_HOME` (or `sdk.dir` in `local.properties`).

```sh
./gradlew spotlessApply              # Explicitly format files
./gradlew spotlessCheck assembleDebug
./gradlew assembleRelease            # Unsigned locally without signingkey.jks
python -m unittest discover -s .github/scripts/tests -p 'test_*.py'
```

Compilation targets JVM 17 and Android 8 (API 26), matching the current Aniyomi
host. `gradle/build-logic` contains the extension, Android library, Kotlin library,
multisrc and formatting plugins. Configuration caching is enabled; source metadata is resolved during configuration and exposed as a cacheable task input. Builds never auto-format source files.

CI keeps the existing module matrix and release publication guards. Infrastructure
changes rebuild every extension. The release signing secrets and APK package
names remain unchanged. Publication still requires the repository signing key.

## Extension declarations

Each extension uses a `build.gradle.kts`:

```kotlin
plugins {
    id("video-extension")
}

videoExtension {
    name = "Example"                 // Romanized APK display name
    sourceClass = ".Example"         // Relative class or factory name
    versionCode = 1                  // Extension revision, 1..999
    contentWarning = 0               // 0 safe, 1 mixed, 2 NSFW
    torrent = false
    // theme = "animestream"         // Optional multisrc library
    // baseUrl = "https://example.com" // Optional deep-link placeholders

    source {
        name = "Example"             // Actual source name, may be non-Latin
        lang = "en"
        baseUrl = "https://example.com"
        // versionId = 1             // Same value as the Kotlin source
        // id = 123L                 // Only when source overrides its generated ID
    }
}
```

Declare **every factory source**. Keep names, language, base URL, versionId and
any explicit ID synchronized with the Kotlin implementations. IDs use Aniyomi's
MD5 algorithm and remain stable across infrastructure upgrades. Source info is
generated into `assets/extension-sources.json` inside each signed APK. Repository
publication reads that metadata directly; it does not load extension classes with
the obsolete upstream Inspector or download an executable at publication time.

`versionName` is `17.<revision>` and Android `versionCode` is
`17000 + revision`. A theme's `extra["baseVersionCode"]` is added to its extension
revision. API upgrades therefore advance Android's code even when the local
revision starts over. The manifest advertises `aniyomix.extensionLib`,
`aniyomix.name`, `aniyomix.contentWarning` and `aniyomix.torrent`; legacy class,
feature and NSFW metadata are retained for repository consumers.

## API 17 playback

- Return `List<Hoster>` from `getHosterList` or `hosterListParse`. Existing
  single-step extractors can return preloaded videos using `toHosterList()`.
- Multi-server sources can return a `Hoster` per server and fetch its video list
  through `getVideoList(hoster)` or `videoListParse(response, hoster)`. Xfani uses
  preloaded current-server videos and deferred alternative servers.
- Construct `Video(videoUrl = ..., videoTitle = ..., initialized = true)` for
  resolved streams. Use `resolution`, `bitrate`, tracks, timestamps and player
  arguments as needed. Use `copy()` when filtering tracks or proxying URLs to
  retain all of that metadata.
- Override `List<Video>.sortVideos()` for preference ordering.
- The host's combined `getAnimeEpisodeUpdate` and `getAnimeSeasonUpdate` defaults
  bridge the existing detail/episode hooks. Override them when a site can fetch
  both in one request. Return existing values for any field not requested.
- API 17 additionally exposes related anime, memo JSON, seek thumbnails and
  `createHttpServer()`. Sources should implement these only where the site
  provides the required data. Existing source-specific HLS proxying is retained.

Old API 14-only apps cannot load these API 17 extensions; update the host first.
