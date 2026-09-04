import json
import os
import re
import subprocess
import sys
from pathlib import Path


EXTENSION_REGEX = re.compile(r"^src/(?P<lang>[^/]+)/(?P<extension>[^/]+)")
MULTISRC_LIB_REGEX = re.compile(r"^lib-multisrc/(?P<multisrc>[^/]+)")
LIB_REGEX = re.compile(r"^lib/(?P<lib>[^/]+)")
CORE_FILES_REGEX = re.compile(
    r"^(buildSrc/|core/|gradle/|build\.gradle\.kts|common\.gradle|"
    r"gradle\.properties|repositories\.gradle\.kts|settings\.gradle\.kts|"
    r"\.github/scripts/)"
)


def run_command(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        sys.exit(result.returncode)
    return result.stdout.strip()


def read_build_file(directory: Path) -> str | None:
    for name in ("build.gradle.kts", "build.gradle"):
        build_file = directory / name
        if build_file.is_file():
            return build_file.read_text(encoding="utf-8")
    return None


def project_dependency_regex(group: str, modules: set[str]) -> re.Pattern[str] | None:
    if not modules:
        return None
    module_pattern = "|".join(map(re.escape, modules))
    return re.compile(
        rf"project\(\s*[\"']:{re.escape(group)}:(?:{module_pattern})[\"']\s*\)"
    )


def resolve_dependent_libs(libs: set[str]) -> set[str]:
    """Resolve /lib modules that transitively depend on changed /lib modules."""
    all_dependent_libs: set[str] = set()
    to_process = set(libs)

    while to_process:
        dependency = project_dependency_regex("lib", to_process)
        to_process = set()

        for lib in Path("lib").iterdir():
            if not lib.is_dir() or lib.name in libs or lib.name in all_dependent_libs:
                continue
            content = read_build_file(lib)
            if content and dependency and dependency.search(content):
                all_dependent_libs.add(lib.name)
                to_process.add(lib.name)

    return all_dependent_libs


def resolve_multisrc_libs(libs: set[str]) -> set[str]:
    """Resolve /lib-multisrc modules that depend on changed /lib modules."""
    dependency = project_dependency_regex("lib", libs)
    if dependency is None:
        return set()

    multisrcs = set()
    for multisrc in Path("lib-multisrc").iterdir():
        if not multisrc.is_dir():
            continue
        content = read_build_file(multisrc)
        if content and dependency.search(content):
            multisrcs.add(multisrc.name)
    return multisrcs


def resolve_extensions(multisrcs: set[str], libs: set[str]) -> set[tuple[str, str]]:
    """Resolve extensions that depend on changed shared modules."""
    patterns = []
    if multisrcs:
        multisrc_pattern = "|".join(map(re.escape, multisrcs))
        patterns.append(rf"themePkg\s*=\s*[\"'](?:{multisrc_pattern})[\"']")
    if libs:
        lib_pattern = "|".join(map(re.escape, libs))
        patterns.append(
            rf"project\(\s*[\"']:lib:(?:{lib_pattern})[\"']\s*\)"
        )
    if not patterns:
        return set()

    dependency = re.compile("|".join(patterns))
    extensions = set()
    for lang in Path("src").iterdir():
        if not lang.is_dir():
            continue
        for extension in lang.iterdir():
            if not extension.is_dir():
                continue
            content = read_build_file(extension)
            if content and dependency.search(content):
                extensions.add((lang.name, extension.name))
    return extensions


def get_all_modules() -> list[str]:
    return sorted(
        f":src:{lang.name}:{extension.name}"
        for lang in Path("src").iterdir()
        if lang.is_dir()
        for extension in lang.iterdir()
        if extension.is_dir() and read_build_file(extension) is not None
    )


def get_module_list(ref: str) -> tuple[list[str], list[str]]:
    diff_output = run_command(
        ["git", "diff", "--name-status", "--find-renames", ref, "HEAD", "--"]
    )
    changed_files = [
        path
        for line in diff_output.splitlines()
        for path in line.split("\t")[1:]
    ]

    modules: set[str] = set()
    multisrcs: set[str] = set()
    libs: set[str] = set()
    replaced_or_deleted: set[str] = set()
    core_files_changed = False

    for file in map(lambda value: Path(value).as_posix(), changed_files):
        if CORE_FILES_REGEX.search(file):
            core_files_changed = True
        elif match := EXTENSION_REGEX.search(file):
            lang = match.group("lang")
            extension = match.group("extension")
            if Path("src", lang, extension).is_dir():
                modules.add(f":src:{lang}:{extension}")
            replaced_or_deleted.add(f"{lang}.{extension}")
        elif match := MULTISRC_LIB_REGEX.search(file):
            multisrc = match.group("multisrc")
            if Path("lib-multisrc", multisrc).is_dir():
                multisrcs.add(multisrc)
            else:
                # A removed shared module can affect dependencies we can no longer inspect.
                core_files_changed = True
        elif match := LIB_REGEX.search(file):
            lib = match.group("lib")
            if Path("lib", lib).is_dir():
                libs.add(lib)
            else:
                core_files_changed = True

    if core_files_changed:
        all_modules = get_all_modules()
        modules.update(all_modules)
        replaced_or_deleted.update(module.removeprefix(":src:").replace(":", ".") for module in all_modules)
        return sorted(modules), sorted(replaced_or_deleted)

    libs.update(resolve_dependent_libs(libs))
    multisrcs.update(resolve_multisrc_libs(libs))
    extensions = resolve_extensions(multisrcs, libs)
    modules.update(f":src:{lang}:{extension}" for lang, extension in extensions)
    replaced_or_deleted.update(f"{lang}.{extension}" for lang, extension in extensions)

    return sorted(modules), sorted(replaced_or_deleted)


def create_matrix(modules: list[str]) -> dict:
    chunk_size = int(os.getenv("CI_CHUNK_SIZE", "65"))
    return {
        "chunk": [
            {"number": index // chunk_size + 1, "modules": modules[index : index + chunk_size]}
            for index in range(0, len(modules), chunk_size)
        ]
    }


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} <base-ref>")

    modules, replaced_or_deleted = get_module_list(sys.argv[1])
    matrix = create_matrix(modules)

    print(
        f"Module chunks to build:\n{json.dumps(matrix, indent=2)}\n\n"
        f"Modules to replace or delete:\n{json.dumps(replaced_or_deleted, indent=2)}"
    )

    if os.getenv("CI") == "true":
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"matrix={json.dumps(matrix)}\n")
            output.write(f"delete={json.dumps(replaced_or_deleted)}\n")


if __name__ == "__main__":
    main()

