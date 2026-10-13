plugins {
    alias(kei.plugins.library)
}

dependencies {
    // Aniyomi's release build is minified without keep rules for NanoHTTPD, so
    // members the host never calls (e.g. newChunkedResponse) are stripped. The
    // child-first extension class loader uses this bundled copy instead.
    implementation(libs.nanohttpd)
}
