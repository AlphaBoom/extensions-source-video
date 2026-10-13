# Extension infrastructure

This repository builds **anime/video extensions** for Aniyomi. The build system,
`core` library and KSP source generation are ported from Keiyoushi and adapted to
Aniyomi's video API (extensions-lib v17); Keiyoushi's manga API and host runtime are
not interchangeable with it.

## Upstream snapshots

Reviewed on 2026-10-13:

- [Keiyoushi](https://github.com/keiyoushi/extensions-source/tree/b5d2cd5c3d23a3408c7daa3eb807668aedfce3e0): `kei.plugins.*` build logic, `keiyoushi { }` DSL, KSP `@Source` processor, `core` utilities, R8 rules. Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.10, SDK 37.
- [Yuzono anime extensions](https://github.com/yuzono/anime-extensions/tree/8bbb8180517b50327ea5965c4629fe15571cbc74): reference for applying the Keiyoushi structure to anime sources. Its Komikku-specific API, Injekt and serialization pins are not copied.
- [Aniyomi extensions-lib v17](https://github.com/aniyomiorg/extensions-lib/tree/971ce96e76202bb35432fe71f92553e3ae40d01d): the authority for video interfaces, manifest metadata and runtime dependencies.
- [Aniyomi host](https://github.com/aniyomiorg/aniyomi/tree/97414446b8a95994c72dd33c41c971a89d4d25b8): API 17 loading and playback behavior.

### Differences from Keiyoushi

| Keiyoushi | This repository |
| --------- | --------------- |
| `HttpSource`, `SourceFactory`, `ConfigurableSource` | `AnimeHttpSource`, `AnimeSourceFactory`, `ConfigurableAnimeSource` in the KSP processor and `KeiSource` |
| `tachiyomix.*` manifest metadata, `Tachiyomi:` label | `aniyomix.*` metadata (plus `aniyomix.torrent`), `Aniyomi:` label, `tachiyomi.animeextension.*` class/NSFW keys |
| `libVersion` 1.4/1.6, required | `libVersion` 17, the default |
| Deeplinks open `eu.kanade.tachiyomi.SEARCH` | `eu.kanade.tachiyomi.ANIMESEARCH` |
| Signed JAR distribution and protobuf repo index | Not ported: Aniyomi installs APKs and reads the JSON index. Source info is packaged as `assets/extension-sources.json` in the APK. |
| `KeiSource` filter cache compressed with zstd-kmp | gzip: Aniyomi's minified build doesn't keep `com.squareup.zstd` |
| Java 11 bytecode | Java 17, the bytecode level of extensions-lib v17 |
| Library namespace `keiyoushi.lib.<module>` | Dashes removed (`keiyoushi.lib.hlsproxy`) |

Host-provided dependencies stay `compileOnly` (`libs.bundles.common`).
Coroutines 1.10.2, serialization 1.9.0, OkHttp 5.4.0, jsoup 1.22.2, NanoHTTPD 2.3.1,
Mihon's Injekt and Cash QuickJS match Aniyomi's runtime rather than the manga host.

Aniyomi's release build is minified and only keeps the public API of the packages
listed in its R8 rules (OkHttp, okio, jsoup, kotlinx, Injekt, QuickJS…). Anything else
the host happens to contain, such as NanoHTTPD or `com.squareup.zstd`, is shrunk and
optimized for the host's own use, so extensions must bundle such libraries
(`implementation`) or avoid them. In the v0.18.2.1 release, NanoHTTPD is present but its
constructors were inlined into `HttpServer`, `IHTTPSession` has no methods left, and
`Response.addHeader` and `Status.lookup` were removed. `lib/hls-proxy` therefore bundles
NanoHTTPD; the child-first extension class loader prefers the bundled copy. For the
same reason, a `createHttpServer()` subclass that reads the request
(`session.uri`, `session.headers`) cannot work on release builds until Aniyomi keeps
`fi.iki.elonen.**`.

## Build

Install JDK 25 and Android SDK packages `platforms;android-37.0` and
`build-tools;37.0.0`. Set `ANDROID_HOME` (or `sdk.dir` in `local.properties`).

```sh
./gradlew spotlessApply
./gradlew :src:zh:xfani:assembleDebug
./gradlew :src:zh:xfani:assembleRelease  # R8-minified, debug-signed without signingkey.jks
python -m unittest discover -s .github/scripts/tests -p 'test_*.py'
```

As in Keiyoushi, `preBuild` runs `spotlessApply` locally and `spotlessCheck` on CI.
Pull requests build release variants so R8 problems surface before merging.

| Path | Purpose |
| ---- | ------- |
| `gradle/build-logic` | `kei.plugins.extension`, `.library`, `.theme` (multisrc), `.android.base`, `.spotless` |
| `gradle/kei.versions.toml` | SDK levels, build tools, Java level and plugin ids |
| `gradle/libs.versions.toml` | Dependency versions and the `common` compileOnly bundle |
| `compiler` | KSP processor generating `keiyoushi.source.Generated` |
| `core` | `keiyoushi.*` utilities, `KeiSource`, `UrlActivity`, default icons, translations |
| `common` | Shared empty manifest and R8 rules for every extension |
| `lib`, `lib-multisrc` | Shared libraries and themes |

CI keeps the existing module matrix and release publication guards. Changes to the
build logic, `compiler`, `core` or `common` rebuild every extension. The release
signing secrets and APK package names remain unchanged.

## Extension declarations

```kotlin
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Example"                    // Romanized APK display name
    versionCode = 1                     // Extension revision, 1..999
    contentWarning = ContentWarning.SAFE // SAFE, MIXED or NSFW
    // torrent = true                   // Aniyomi only
    // theme = "<theme>"                // lib-multisrc theme
    // pkgName = "en.oldname"           // Keep an old package name after renaming the folder

    source {
        // name = "示例"                 // Defaults to the extension name
        lang = "en"
        baseUrl = "https://example.com"
        // baseUrl { mirrors("https://a.example", "https://b.example") }
        // baseUrl { custom("https://example.com") }
        // versionId = 2
        // id = 123L                    // Keep an existing ID after renaming
    }

    deeplink {
        // host("www.example.com")      // Defaults to the hosts of all source base URLs
        path("/anime/..*")
    }
}
```

The source class is annotated instead of named in the build file:

```kotlin
@Source
abstract class Example : AnimeHttpSource() {
    // name, lang, id and baseUrl are generated from the source { } blocks
}
```

The processor generates `keiyoushi.source.Generated`, the class the manifest points
to. One `source { }` block produces a subclass; several produce an
`AnimeSourceFactory` with one anonymous subclass per block. A concrete `@Source`
class taking `lang` and `id` (and optionally `name`/`baseUrl`) constructor parameters
is instantiated directly instead. `mirrors(...)` and `custom(...)` base URLs add the
matching preference automatically.

IDs use Aniyomi's MD5 algorithm (`name.lowercase()/lang/versionId`) and must stay
stable: keep `name`, `lang` and `versionId`, or set `id`, when renaming.

The extension manifest is generated: `AndroidManifest.xml` is only needed for
additional components. Deeplinks route through `keiyoushi.source.UrlActivity`, which
passes the full URL as the search query; `KeiSource` resolves it with
`getAnimeByUrl`, other sources check for URL queries in `getSearchAnime`.

`versionName` is `17.<versionCode>` and Android's `versionCode` is
`17000 + versionCode`. A theme's `baseVersionCode` is added to the extension's.

Per-module R8 rules go in `proguard-rules.pro` next to `build.gradle.kts` (see
`src/zh/anime1` for a reflective dependency).

## Sources and core utilities

`keiyoushi.source.KeiSource` is the suspend-first base class: it configures the
client (compression, caching, rate limiter ordering), adds `Referer`/`Origin`, splits
URL queries from searches, caches network-fetched filters, and requires
`fetchAnimeEpisodeUpdate` and `getHosterList`. Existing sources extending
`AnimeHttpSource` directly keep working and can use the utilities without it:

- `keiyoushi.utils`: `parseAs`, `toJsonRequestBody`, `getPreferences`/`getPreferencesLazy`, date parsing, crypto, Next.js and React Flight parsing, GraphQL, protobuf, WebView and Turnstile helpers.
- `keiyoushi.network`: `rateLimit`, cookies, cache control and OkHttp helpers using the current source as context.
- `keiyoushi.zip`: remote ZIP reading.
- Translations for generated preferences live in `core/translations/strings.json`.

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

Use [official Aniyomi v0.18.2.1 or newer](https://github.com/aniyomiorg/aniyomi/releases/tag/v0.18.2.1), whose release notes explicitly add API 17 support. Old API 14-only apps cannot load these extensions; update the host first.
