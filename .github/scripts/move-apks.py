import json
import os
import shutil
from pathlib import Path


ARTIFACTS_DIR = Path(os.getenv("ARTIFACTS_DIR", Path.home() / "apk-artifacts"))
REPO_DIR = Path(os.getenv("REPO_DIR", "repo"))
REPO_APK_DIR = REPO_DIR / "apk"

REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

# Reconcile with the source tree even when the diff uses the empty-tree fallback.
current_modules = {
    f"{extension.parent.name}.{extension.name}"
    for extension in Path("src").glob("*/*")
    if any((extension / name).is_file() for name in ("build.gradle", "build.gradle.kts"))
}
replaced_or_deleted = set(json.loads(os.getenv("DELETE", "[]")))

for old_apk in REPO_APK_DIR.glob("aniyomi-*-v*.apk"):
    module = old_apk.name.removeprefix("aniyomi-").rsplit("-v", 1)[0]
    if module not in current_modules or module in replaced_or_deleted:
        old_apk.unlink()

# Replace changed APKs while keeping every unaffected APK in the repository.
for apk in ARTIFACTS_DIR.glob("**/*.apk"):
    apk_name = apk.name.replace("-release.apk", ".apk")
    apk_prefix = apk_name.rsplit("-v", 1)[0]

    for old_apk in REPO_APK_DIR.glob(f"{apk_prefix}-v*.apk"):
        old_apk.unlink()

    shutil.move(apk, REPO_APK_DIR / apk_name)
