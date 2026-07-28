# HLS Proxy

A loopback HTTP proxy that gives Aniyomi's player a conventional HLS transport
while extensions retain control of upstream requests, playlists, and media
resources.

The design builds on the layering and disguise-byte detection used by
[`yuzono/anime-extensions`' m3u8server][upstream], with these changes:

- binds only to `127.0.0.1`;
- keeps upstream URLs and headers behind opaque, expiring session IDs;
- streams resources unless a body transformer explicitly requires buffering;
- forwards range and cache-validation headers;
- recognizes playlist, key, init, partial, and media URIs by HLS tags rather
  than file extensions;
- exposes independent playlist, request, and body transformation APIs.

The upstream project is licensed under Apache-2.0. The optional
`FakeImageJunkTransformer` derives its detection strategy from its
`AutoDetector` and has been modified for streaming proxy integration.

## Basic usage

```kotlin
private val hlsProxy by lazy { HlsProxy(client) }

val localUrl = hlsProxy.proxy(
    playlistUrl = upstreamUrl,
    headers = videoHeaders,
)
```

## Playlist filtering

Playlist transformers run before proxy URLs are inserted, so they can remove
ads or repair malformed manifests using the original upstream URLs:

```kotlin
val options = HlsProxyOptions(
    playlistTransformers = listOf(
        HlsPlaylistTransformer { _, playlist ->
            filterAds(playlist)
        },
    ),
)
```

Transformers receive each nested playlist independently. A transformer that
removes media segments is responsible for keeping the associated HLS tags
(`EXTINF`, `BYTERANGE`, encryption state, discontinuities, and media sequence)
consistent.

## Segment deobfuscation

`FakeImageJunkTransformer` can strip injected JPEG/PNG/GIF disguise blocks. It
is opt-in because it buffers the complete segment. When a body transformer is
active, the proxy fetches the complete upstream resource and applies client
byte ranges to the transformed result:

```kotlin
val options = HlsProxyOptions(
    bodyTransformers = listOf(FakeImageJunkTransformer),
)
```

[upstream]: https://github.com/yuzono/anime-extensions/tree/master/lib/m3u8server
