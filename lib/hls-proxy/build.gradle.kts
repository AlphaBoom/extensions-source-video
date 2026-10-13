plugins {
    alias(kei.plugins.library)
}

dependencies {
    // Aniyomi's release build contains NanoHTTPD, but R8 optimizes it without keep
    // rules: in v0.18.2.1 the NanoHTTPD(String, int) constructor, the
    // IHTTPSession getters and Response.addHeader no longer exist. The child-first
    // extension class loader uses this bundled copy instead.
    implementation(libs.nanohttpd)
}
