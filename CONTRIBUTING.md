# Contributing

> Extensions target **Aniyomi extensions-lib API 17** and use the build system, KSP
> source generation and `core` library ported from Keiyoushi. See
> [the infrastructure guide](docs/infrastructure.md) for the plugins, `KeiSource` and API 17 details.

This guide have some instructions and tips on how to create a new Aniyomi extension. Please **read it carefully** if you're a new contributor or don't have any experience on the required languages and knowledges.

This guide is not definitive and it's being updated over time. If you find any issue on it, feel free to report it through a [Meta Issue](https://github.com/aniyomiorg/aniyomi-extensions/issues/new?assignees=&labels=Meta+request&template=request_meta.yml) or fixing it directly by submitting a Pull Request.

## Table of Contents

1. [Prerequisites](#prerequisites)
   1. [Tools](#tools)
   2. [Cloning the repository](#cloning-the-repository)
2. [Getting help](#getting-help)
3. [Writing an extension](#writing-an-extension)
   1. [Setting up a new Gradle module](#setting-up-a-new-gradle-module)
   2. [Core dependencies](#core-dependencies)
   3. [Extension main class](#extension-main-class)
   4. [Extension call flow](#extension-call-flow)
   5. [Misc notes](#misc-notes)
   6. [Advanced extension features](#advanced-extension-features)
4. [Multi-source themes](#multi-source-themes)
   1. [The directory structure](#the-directory-structure)
   2. [Additional Notes](#additional-notes)
5. [Running](#running)
6. [Debugging](#debugging)
   1. [Android Debugger](#android-debugger)
   2. [Logs](#logs)
   3. [Inspecting network calls](#inspecting-network-calls)
   4. [Using external network inspecting tools](#using-external-network-inspecting-tools)
7. [Building](#building)
8. [Submitting the changes](#submitting-the-changes)
   1. [Pull Request checklist](#pull-request-checklist)

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)
- Web scraping
    - [HTML](https://developer.mozilla.org/en-US/docs/Web/HTML)
    - [CSS selectors](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
    - [OkHttp](https://square.github.io/okhttp/)
    - [JSoup](https://jsoup.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- [JDK 25](https://adoptium.net/) and Android SDK packages `platforms;android-37.0` and `build-tools;37.0.0`
- Emulator or phone (Android 8 or newer) with developer options enabled and Aniyomi v0.18.2.1 or newer installed
- [Icon Generator](https://as280093.github.io/AndroidAssetStudio/icons-launcher.html)

### Cloning the repository

Some alternative steps can be followed to ignore "repo" branch and skip unrelated sources, which will make it faster to pull, navigate and build. This will also reduce disk usage and network traffic.

<details><summary>Steps</summary>

1. Make sure to delete "repo" branch in your fork. You may also want to disable Actions in the repo settings.

    **Also make sure you are using the latest version of Git as many commands used here are pretty new.**

2. Do a partial clone.
    ```bash
    git clone --filter=blob:none --sparse <fork-repo-url>
    cd aniyomi-extensions/
    ```
3. Configure sparse checkout.

    There are two modes of pattern matching. The default is cone (🔺) mode.
    Cone mode enables significantly faster pattern matching for big monorepos
    and the sparse index feature to make Git commands more responsive.
    In this mode, you can only filter by file path, which is less flexible
    and might require more work when the project structure changes.

    You can skip this code block to use legacy mode if you want easier filters.
    It won't be much slower as the repo doesn't have that many files.

    To enable cone mode together with sparse index, follow these steps:

    ```bash
    git sparse-checkout set --cone --sparse-index
    # add project folders
    git sparse-checkout add common core gradle lib
    # add a single source
    git sparse-checkout add src/<lang>/<source>
    # add a multisrc theme
    git sparse-checkout add lib-multisrc/<theme>
    ```

    To remove a source, open `.git/info/sparse-checkout` and delete the exact
    lines you typed when adding it. Don't touch the other auto-generated lines
    unless you fully understand how cone mode works, or you might break it.

    To use the legacy non-cone mode, follow these steps:

    ```bash
    # enable sparse checkout
    git sparse-checkout set --no-cone
    # edit sparse checkout filter
    vim .git/info/sparse-checkout
    # alternatively, if you have VS Code installed
    code .git/info/sparse-checkout
    ```
    Here's an example:
    ```bash
    /*
    !/src/*
    !/lib-multisrc/*
    # allow a single source
    /src/<lang>/<source>
    # allow a multisrc theme
    /lib-multisrc/<theme>
    # or type the source name directly
    <source>
    ```

    Explanation: the rules are like `gitignore`. We first exclude all sources
    while retaining project folders, then add the needed sources back manually.

4. Configure remotes.
    ```bash
    # add upstream
    git remote add upstream <aniyomiorg-repo-url>
    # optionally disable push to upstream
    git remote set-url --push upstream no_pushing
    # ignore 'repo' branch of upstream
    # option 1: use negative refspec
    git config --add remote.upstream.fetch "^refs/heads/repo"
    # option 2: fetch master only (ignore all other branches)
    git config remote.upstream.fetch "+refs/heads/master:refs/remotes/upstream/master"
    # update remotes
    git remote update
    # track master of upstream instead of fork
    git branch master -u upstream/master
    ```
5. Useful configurations. (optional)
    ```bash
    # prune obsolete remote branches on fetch
    git config remote.origin.prune true
    # fast-forward only when pulling master branch
    git config pull.ff only
    # Add an alias to sync master branch without fetching useless blobs.
    # If you run `git pull` to fast-forward in a blobless clone like this,
    # all blobs (files) in the new commits are still fetched regardless of
    # sparse rules, which makes the local repo accumulate unused files.
    # Use `git sync-master` to avoid this. Be careful if you have changes
    # on master branch, which is not a good practice.
    git config alias.sync-master '!git switch master && git fetch upstream && git reset --keep FETCH_HEAD'
    ```
6. Later, if you change the sparse checkout filter, run `git sparse-checkout reapply`.

Read more on
[Git's object model](https://github.blog/2020-12-17-commits-are-snapshots-not-diffs/),
[partial clone](https://github.blog/2020-12-21-get-up-to-speed-with-partial-clone-and-shallow-clone/),
[sparse checkout](https://github.blog/2020-01-17-bring-your-monorepo-down-to-size-with-sparse-checkout/),
[sparse index](https://github.blog/2021-11-10-make-your-monorepo-feel-small-with-gits-sparse-index/),
and [negative refspecs](https://github.blog/2020-10-19-git-2-29-released/#user-content-negative-refspecs).
</details>

## Getting help

- Join [the Discord server](https://discord.gg/F32UjdJZrR) for online help and to ask questions while developing your extension. When doing so, please ask it in the `#dev` channel.

- There are some features and tricks that are not explored in this document. Refer to existing extension code for examples.

## Writing an extension

The quickest way to get started is to copy an existing extension's folder structure and renaming it as needed. We also recommend reading through a few existing extensions' code before you start.

### Setting up a new Gradle module

Each extension should reside in `src/<lang>/<mysourcename>`. Use `all` as `<lang>` if your target source supports multiple languages or if it could support multiple sources.

The `<lang>` used in the folder inside `src` should be the major `language` part. For example, if you will be creating a `pt-BR` source, use `<lang>` here as `pt` only. Inside the source class, use the full locale string instead.

#### Extension file structure

The simplest extension structure looks like this:

```console
$ tree src/<lang>/<mysourcename>/
src/<lang>/<mysourcename>/
├── build.gradle.kts
├── proguard-rules.pro (optional)
├── res
│   ├── mipmap-hdpi
│   │   └── ic_launcher.png
│   ├── mipmap-mdpi
│   │   └── ic_launcher.png
│   ├── mipmap-xhdpi
│   │   └── ic_launcher.png
│   ├── mipmap-xxhdpi
│   │   └── ic_launcher.png
│   ├── mipmap-xxxhdpi
│   │   └── ic_launcher.png
│   └── web_hi_res_512.png
└── src
    └── eu
        └── kanade
            └── tachiyomi
                └── animeextension
                    └── <lang>
                        └── <mysourcename>
                            └── <MySourceName>.kt

13 directories, 9 files
```

`<lang>` should be an ISO 639-1 compliant language code (two letters or `all`). `<mysourcename>`
should be adapted from the site name, and can only contain lowercase ASCII letters and digits.
Your extension code must be placed in the package `eu.kanade.tachiyomi.animeextension.<lang>.<mysourcename>`.

#### build.gradle.kts
Make sure that your new extension's `build.gradle.kts` file follows the following structure:

```kotlin
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "<My source name>"
    versionCode = 1
    contentWarning = ContentWarning.SAFE

    source {
        lang = "<lang>"
        baseUrl = "https://example.com"
    }
}
```

| Field | Description |
| ----- | ----------- |
| `name` | The name of the extension. Should be romanized if site name is not in English. |
| `versionCode` | The extension revision (1..999). It must be incremented with any change to the code. |
| `contentWarning` | `ContentWarning.SAFE`, `MIXED` or `NSFW`. Anything but `SAFE` also sets the legacy NSFW flag. |
| `torrent` | (Optional, defaults to `false`) Whether the extension returns torrent videos. |
| `theme` | (Optional) Name of a `lib-multisrc` theme the extension builds upon. See [Multi-source themes](#multi-source-themes). |
| `pkgName` | (Optional) Overrides the `<lang>.<folder>` package suffix, to keep an old package after renaming the folder. |
| `source { }` | One block for **every** source: `lang`, `baseUrl` (or `baseUrl { mirrors(...) }` / `baseUrl { custom(...) }`), and optionally `name` (defaults to the extension name), `versionId` and `id`. The KSP processor generates these members, and the metadata is packaged into the APK and published to the repository index. |
| `deeplink { }` | (Optional) URL patterns the extension opens. See [URL intent filter](#url-intent-filter). |

The extension's version name is `17.<versionCode>` and the Android version code is `17000 + versionCode`.
With the example used above, the version would be `17.1`.

The manifest is generated from these values. Add an `AndroidManifest.xml` only for extra components,
and a `proguard-rules.pro` when a bundled dependency needs R8 keep rules: release APKs are minified.

### Core dependencies

#### Extension API

Extensions rely on [extensions-lib](https://github.com/aniyomiorg/extensions-lib), which provides some interfaces and stubs from the [app](https://github.com/aniyomiorg/aniyomi) for compilation purposes. The actual implementations can be found [here](https://github.com/aniyomiorg/aniyomi/tree/master/app/src/main/java/eu/kanade/tachiyomi/animesource). Referencing the actual implementation will help with understanding extensions' call flow.

#### Shared libraries

Reusable code lives in `lib/<name>` and is added with `implementation(project(":lib:<name>"))`:

| Library | Purpose |
| ------- | ------- |
| `playlist-utils` | Parses HLS/DASH playlists into `Video`s with resolution, bitrate and tracks. |
| `hls-proxy` | Local HLS proxy for streams that need playlist/segment rewriting or authenticated requests. |
| `i18n` | Loads translated strings for multi-language extensions. |
| `bangumi-scraper` | Fetches anime metadata from Bangumi. |

Only add a new library when more than one extension needs it; unused libraries are removed.

#### Additional dependencies

If you find yourself needing additional functionality, you can add more dependencies to your `build.gradle.kts` file.
Many of [the dependencies](https://github.com/aniyomiorg/aniyomi/blob/master/app/build.gradle.kts) from the main Aniyomi app are exposed to extensions by default.

> Note that several dependencies are already exposed to all extensions via Gradle version catalog.
> To view which are available view `libs.versions.toml` under the `gradle` folder

Notice that we're using `compileOnly` instead of `implementation` if the app already contains it. You could use `implementation` instead for a new dependency, or you prefer not to rely on whatever the main app has at the expense of app size.

Note that using `compileOnly` restricts you to versions that must be compatible with those used in [the latest stable version of Aniyomi](https://github.com/aniyomiorg/aniyomi/releases/latest).

### Extension main class

The source class is annotated with `@Source` (`keiyoushi.annotation.Source`), and should be `abstract` and extend `KeiSource`, `AnimeHttpSource` or `ParsedAnimeHttpSource`. Don't override `name`, `lang`, `id` or `versionId`, nor a static `baseUrl`: the KSP processor generates them from the `source { }` blocks in a `keiyoushi.source.Generated` subclass, or in an `AnimeSourceFactory` when there are several blocks.

```kotlin
@Source
abstract class MySourceName : AnimeHttpSource() {
    // ...
}
```

| Class | Description |
| ----- | ----------- |
| `KeiSource` | Suspend-first base class from `core` with client/header defaults, URL search, fetched filter caching and combined episode updates. |
| `AnimeHttpSource`| For online source, where requests are made using HTTP. |
| `ParsedAnimeHttpSource`| Similar to `AnimeHttpSource`, but has methods useful for scraping pages. |

#### Main class key variables

| Field | Description |
| ----- | ----------- |
| `name` | Name displayed in the "Sources" tab in Aniyomi. |
| `baseUrl` | Base URL of the source without any trailing slashes. |
| `lang` | An ISO 639-1 compliant language code (two letters in lower case in most cases, but can also include the country/dialect part by using a simple dash character). |
| `id` | Identifier of your source, generated from `name`, `lang` and `versionId`. Set `id` in the `source { }` block only to keep an existing ID. |

Multiple languages or mirrors of the same website are declared as several `source { }` blocks; the generated factory creates one instance per block. For similar websites use [theme sources](#multi-source-themes).

### Extension call flow

#### Popular Anime

a.k.a. the Browse source entry point in the app (invoked by tapping on the source name).

- The app calls `fetchPopularAnime` which should return a `AnimesPage` containing the first batch of found `SAnime` entries.
    - This method supports pagination. When user scrolls the manga list and more results must be fetched, the app calls it again with increasing `page` values(starting with `page=1`). This continues while `AnimesPage.hasNextPage` is passed as `true` and `AnimesPage.mangas` is not empty.
- To show the list properly, the app needs `url`, `title` and `thumbnail_url`. You **must** set them here. The rest of the fields could be filled later.(refer to Anime Details below).
    - You should set `thumbnail_url` if is available, if not, `fetchAnimeDetails` will be **immediately** called. (this will increase network calls heavily and should be avoided).

#### Latest Anime

a.k.a. the Latest source entry point in the app (invoked by tapping on the "Latest" button beside the source name).

- Enabled if `supportsLatest` is `true` for a source
- Similar to popular anime, but should be fetching the latest entries from a source.

#### Anime Search

- When the user searches inside the app, `fetchSearchAnime` will be called and the rest of the flow is similar to what happens with `fetchPopularAnime`.
    - If search functionality is not available, return `Observable.just(AnimesPage(emptyList(), false))`
- `getFilterList` will be called to get all filters and filter types.

##### Filters

The search flow have support to filters that can be added to a `AnimeFilterList` inside the `getFilterList` method. When the user changes the filter's state, they will be passed to the `searchAnimeRequest`, and they can be iterated to create the request (by getting the `filter.state` value, where the type varies depending on the `AnimeFilter` used). You can check the filter types available [here](https://github.com/aniyomiorg/aniyomi/blob/master/source-api/src/main/java/eu/kanade/tachiyomi/animesource/model/AnimeFilter.kt) and in the table below.

| Filter | State type | Description |
| ------ | ---------- | ----------- |
| `AnimeFilter.Header` | None | A simple header. Useful for separating sections in the list or showing any note or warning to the user. |
| `AnimeFilter.Separator` | None | A line separator. Useful for visual distinction between sections. |
| `AnimeFilter.Select<V>` | `Int` | A select control, similar to HTML's `<select>`. Only one item can be selected, and the state is the index of the selected one. |
| `AnimeFilter.Text` | `String` | A text control, similar to HTML's `<input type="text">`. |
| `AnimeFilter.CheckBox` | `Boolean` | A checkbox control, similar to HTML's `<input type="checkbox">`. The state is `true` if it's checked. |
| `AnimeFilter.TriState` | `Int` | A enhanced checkbox control that supports an excluding state. The state can be compared with `STATE_IGNORE`, `STATE_INCLUDE` and `STATE_EXCLUDE` constants of the class. |
| `AnimeFilter.Group<V>` | `List<V>` | A group of filters (preferentially of the same type). The state will be a `List` with all the states. |
| `AnimeFilter.Sort` | `Selection` | A control for sorting, with support for the ordering. The state indicates which item index is selected and if the sorting is `ascending`. |

All control filters can have a default state set. It's usually recommended if the source have filters to make the initial state match the popular anime list, so when the user open the filter sheet, the state is equal and represents the current anime showing.

The `AnimeFilter` classes can also be extended, so you can create new custom filters like the `UriPartFilter`:

```kotlin
open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
    AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}
```

#### Anime Details

- When user taps on an anime, `fetchAnimeDetails` and `fetchEpisodeList` will be called and the results will be cached.
    - A `SAnime` entry is identified by its `url`.
- `fetchAnimeDetails` is called to update an anime's details from when it was initialized earlier.
    - `SAnime.initialized` tells the app if it should call `fetchAnimeDetails`. If you are overriding `fetchAnimeDetails`, make sure to pass it as `true`.
    - `SAnime.genre` is a string containing list of all genres separated with `", "`.
    - `SAnime.status` is an "enum" value. Refer to [the values in the `SAnime` companion object](https://github.com/aniyomiorg/extensions-lib/blob/main/library/src/main/java/eu/kanade/tachiyomi/animesource/model/SAnime.kt#L26-L32).
    - During a backup, only `url` and `title` are stored. To restore the rest of the anime data, the app calls `fetchAnimeDetails`, so all fields should be (re)filled in if possible.
    - If a `SAnime` is cached `fetchAnimeDetails` will be only called when the user does a manual update(Swipe-to-Refresh).
- `fetchEpisodeList` is called to display the episode list.
    - **The list should be sorted descending by the source order**.
    - If `Video.videoUrl`s are available immediately, you should pass them here. Otherwise, you should set `video.url` to a page that contains them and override `videoUrlParse` to fill those `videoUrl`s.

#### Episode

- After an episode list for the anime is fetched and the app is going to cache the data, `prepareNewEpisode` will be called.
- `SEpisode.date_upload` is the [UNIX Epoch time](https://en.wikipedia.org/wiki/Unix_time) **expressed in milliseconds**.
    - If you don't pass `SEpisode.date_upload` and leave it zero, the app will use the default date instead, but it's recommended to always fill it if it's available.
    - To get the time in milliseconds from a date string, you can use a `SimpleDateFormat` like in the example below.

      ```kotlin
      private fun parseDate(dateStr: String): Long {
          return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
              .getOrNull() ?: 0L
      }

      companion object {
          private val DATE_FORMATTER by lazy {
              SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
          }
      }
      ```

      Make sure you make the `SimpleDateFormat` a class constant or variable so it doesn't get recreated for every episode. If you need to parse or format dates in anime description, create another instance since `SimpleDateFormat` is not thread-safe.
    - If the parsing have any problem, make sure to return `0L` so the app will use the default date instead.
    - The app will overwrite dates of existing old episodes **UNLESS** `0L` is returned.
    - The default date has [changed](https://github.com/aniyomiorg/aniyomi/pull/7197) in Aniyomi preview ≥ r4442 or stable > 0.13.4.
        - In older versions, the default date is always the fetch date.
        - In newer versions, this is the same if every (new) episode has `0L` returned.
        - However, if the source only provides the upload date of the latest episode, you can now set it to the latest episode and leave other episodes default. The app will automatically set it (instead of fetch date) to every new episode and leave old episodes' dates untouched.

#### Episode Videos

- When user opens an episode, `getHosterList` (or `hosterListParse` for `AnimeHttpSource`) is called and returns a list of `Hoster`s, usually one per server.
- Each hoster's videos are resolved lazily through `getVideoList(hoster)` / `videoListParse(response, hoster)`. Sources that resolve every video up front can return `videos.toHosterList()`.
- Construct resolved streams with `Video(videoUrl = ..., videoTitle = ..., initialized = true)` and fill `resolution`, `bitrate`, `headers`, `subtitleTracks` and `audioTracks` when known. Use `video.copy(...)` instead of building a new `Video` when filtering tracks or rewriting URLs, so the remaining metadata is kept.
- Override `List<Video>.sortVideos()` to apply the user's quality/server preferences.

### Misc notes

- Sometimes you may find no use for some inherited methods. If so just override them and throw exceptions: `throw UnsupportedOperationException()`
- You probably will find `getUrlWithoutDomain` useful when parsing the target source URLs. Keep in mind there's a current issue with spaces in the URL though, so if you use it, replace all spaces with URL encoded characters (like `%20`).
- If possible try to stick to the general workflow from `AnimeHttpSource`/`AnimeParsedHttpSource`; breaking them may cause you more headache than necessary.
- By implementing `ConfigurableAnimeSource` you can add settings to your source, which is backed by [`SharedPreferences`](https://developer.android.com/reference/android/content/SharedPreferences).

### Advanced Extension features

#### URL intent filter

Extensions declare the URLs they open with `deeplink { }` blocks in `build.gradle.kts`:

```kotlin
keiyoushi {
    deeplink {
        host("www.example.com") // Optional, defaults to the hosts of the source base URLs
        path("/anime/..*")
    }
}
```

The generated manifest routes them to `keiyoushi.source.UrlActivity`, which opens Aniyomi's search with the full URL as the query. Sources extending `KeiSource` resolve it in `getAnimeByUrl`; other sources should check for URL queries in `getSearchAnime`.

To test if the URL intent filter is working as expected, you can try opening the website in a browser and navigating to the endpoint that was added as a filter or clicking a hyperlink. Alternatively, you can use the `adb` command below.

```console
$ adb shell am start -d "<your-link>" -a android.intent.action.VIEW
```

#### Renaming existing sources

There is some cases where existing sources changes their name on the website. To correctly reflect these changes in the extension, you need to explicity set the `id` to the same old value, otherwise it will get changed by the new `name` value and users will be forced to migrate back to the source.

To get the current `id` value before the name change, you can search the source name in the [repository JSON file](https://github.com/AlphaBoom/extensions-source-video/blob/repo/index.min.json) by looking into the `sources` attribute of the extension. When you have the `id` copied, set it in the source block:

```kotlin
source {
    name = "<new name>"
    lang = "<lang>"
    baseUrl = "https://example.com"
    id = <the-id>L
}
```

Then the class name and the `name` value can be changed.

**Important:** the package name **needs** to be the same (even if it has the old name), otherwise users will not receive the extension update when it gets published in the repository. The package name comes from the `src/<lang>/<folder>` directory: keep the folder name, or set `pkgName` to the old suffix.

The `id` also needs to be explicity set to the old value if you're changing the `lang` attribute.

## Multi-source themes
The `lib-multisrc` directory houses shared code for cases where multiple source sites use the same site generator tool (usually a CMS) for bootstraping their website, which makes them similar enough to prompt code reuse through inheritance; from now on we will use the general **theme** term to refer to this.

Each theme is an Android library containing the *default implementation*. Every site using it is still a regular extension under `src/<lang>/<source>` that subclasses the theme.

### The directory structure
```console
lib-multisrc/<theme>
├── AndroidManifest.xml
├── build.gradle.kts
├── res (optional, default icons)
└── src
    └── eu/kanade/tachiyomi/multisrc/<theme>
        └── <Theme>.kt
```

A theme's `build.gradle.kts`:

```kotlin
plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 1
    // deeplink { path("/anime/..*") } // Shared by every extension using the theme
}
```

An extension using the theme declares it in its own `build.gradle.kts`, and its `@Source` class extends the theme:

```kotlin
keiyoushi {
    name = "<My source name>"
    theme = "<theme>"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    source { ... }
}
```

### Additional Notes
- A themed extension's revision is `baseVersionCode + versionCode`.
    - When a theme's default implementation changes, increase its `baseVersionCode`; CI bumps every extension depending on it.
    - When only one site changes, increase that extension's `versionCode`.
- Resources of the extension override the theme's default icons.

## Running

To make local development more convenient, you can use the following run configuration to launch Aniyomi directly at the Browse panel:

![](https://i.imgur.com/STy0UFY.png)

If you're running a Preview or debug build of Aniyomi:

```
-W -S -n xyz.jmir.tachiyomi.mi.debug/eu.kanade.tachiyomi.ui.main.MainActivity -a eu.kanade.tachiyomi.SHOW_CATALOGUES
```

And for a release build of Aniyomi:

```
-W -S -n xyz.jmir.tachiyomi.mi/eu.kanade.tachiyomi.ui.main.MainActivity -a eu.kanade.tachiyomi.SHOW_CATALOGUES
```

If you're deploying to Android 11 or higher, enable the "Always install with package manager" option in the run configurations.

## Debugging

### Android Debugger

You can leverage the Android Debugger to step through your extension while debugging.

You *cannot* simply use Android Studio's `Debug 'module.name'` -> this will most likely result in an error while launching.

Instead, once you've built and installed your extension on the target device, use `Attach Debugger to Android Process` to start debugging Aniyomi.

![](https://i.imgur.com/muhXyfu.png)


### Logs

You can also elect to simply rely on logs printed from your extension, which
show up in the [`Logcat`](https://developer.android.com/studio/debug/am-logcat) panel of Android Studio.

### Inspecting network calls
One of the easiest way to inspect network issues (such as HTTP errors 404, 429, no chapter found etc.) is to use the [`Logcat`](https://developer.android.com/studio/debug/am-logcat) panel of Android Studio and filtering by the `OkHttpClient` tag.

To be able to check the calls done by OkHttp, you need to enable verbose logging in the app, that is not enabled by default and is only included in the Preview versions of Aniyomi. To enable it, go to More -> Settings -> Advanced -> Verbose logging. After enabling it, don't forget to restart the app.

Inspecting the Logcat allows you to get a good look at the call flow and it's more than enough in most cases where issues occurs. However, alternatively, you can also use an external tool like `mitm-proxy`. For that, refer to the next section.

### Using external network inspecting tools
If you want to take a deeper look into the network flow, such as taking a look into the request and response bodies, you can use an external tool like `mitm-proxy`.

#### Setup your proxy server
We are going to use [mitm-proxy](https://mitmproxy.org/) but you can replace it with any other Web Debugger (i.e. Charles, Burp Suite, Fiddler etc). To install and execute, follow the commands bellow.

```console
Install the tool.
$ sudo pip3 install mitmproxy
Execute the web interface and the proxy.
$ mitmweb
```

Alternatively, you can also use the Docker image:

```
$ docker run --rm -it -p 8080:8080 \
    -p 127.0.0.1:8081:8081 \
    --web-host 0.0.0.0 \
    mitmproxy/mitmproxy mitmweb
```

After installing and running, open your browser and navigate to http://127.0.0.1:8081.

#### OkHttp proxy setup
Since most of the manga sources are going to use HTTPS, we need to disable SSL verification in order to use the web debugger. For that, add this code to inside your source class:

```kotlin
package eu.kanade.tachiyomi.animeextension.en.animesource

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.multisrc.animetheme.AnimeTheme
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AnimeSource : AnimeTheme(
    "AnimeSource",
    "https://example.com",
    "en"
) {
    private fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
        val naiveTrustManager = @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        }

        val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
            val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
            init(null, trustAllCerts, SecureRandom())
        }.socketFactory

        sslSocketFactory(insecureSocketFactory, naiveTrustManager)
        hostnameVerifier { _, _ -> true }
        return this
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .ignoreAllSSLErrors()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("10.0.2.2", 8080)))
        .build()
}
```

Note: `10.0.2.2` is usually the address of your loopback interface in the android emulator. If Aniyomi tells you that it's unable to connect to 10.0.2.2:8080 you will likely need to change it (the same if you are using hardware device).

If all went well, you should see all requests and responses made by the source in the web interface of `mitmweb`.

## Building

APKs can be created in Android Studio via `Build > Build Bundle(s) / APK(s) > Build APK(s)`, or from the command line:

```console
$ ./gradlew :src:<lang>:<source>:assembleDebug     # also runs spotlessApply
$ ./gradlew :src:<lang>:<source>:assembleRelease   # R8-minified, as published
```

## Submitting the changes

When you feel confident about your changes, submit a new Pull Request so your code can be reviewed and merged if it's approved. We encourage following a [GitHub Standard Fork & Pull Request Workflow](https://gist.github.com/Chaser324/ce0505fbed06b947d962) and following the good practices of the workflow, such as not commiting directly to `master`: always create a new branch for your changes.

If you are more comfortable about using Git GUI-based tools, you can refer to [this guide](https://learntodroid.com/how-to-use-git-and-github-in-android-studio/) about the Git integration inside Android Studio, specifically the "How to Contribute to an to Existing Git Repository in Android Studio" section of the guide.

Please **do test your changes by compiling it through Android Studio** before submitting it. Also make sure to follow the PR checklist available in the PR body field when creating a new PR. As a reference, you can find it below.

### Pull Request checklist

- Update `versionCode` in `build.gradle.kts` for individual extensions
- Update `baseVersionCode` in a theme's `build.gradle.kts` when the theme changes
- Reference all related issues in the PR body (e.g. "Closes #xyz")
- Set `contentWarning` in `build.gradle.kts` when appropriate
- Declare every source in a `source { }` block and annotate the source class with `@Source`
- Explicitly kept the `id` if a source's name or language were changed
- Run `./gradlew spotlessCheck` and build the release variant
- Test the modifications by compiling and running the extension through Android Studio
