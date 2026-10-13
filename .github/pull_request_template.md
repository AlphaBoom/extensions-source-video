Checklist:

- [ ] Updated `versionCode` in `build.gradle.kts` for individual extensions
- [ ] Updated `extra["baseVersionCode"]` as needed for changed multisrc themes
- [ ] Referenced all related issues in the PR body (e.g. "Closes #xyz")
- [ ] Set `contentWarning` in `build.gradle.kts` when appropriate
- [ ] Kept the `source { }` blocks in `build.gradle.kts` in sync with the Kotlin sources
- [ ] Have not changed source names
- [ ] Have explicitly kept the `id` if a source's name or language were changed
- [ ] Have run `./gradlew spotlessCheck` and tested the modifications by compiling and running the extension in an API 17 host
- [ ] Have removed `web_hi_res_512.png` when adding a new extension
- [ ] Have made sure all the icons are in png format
