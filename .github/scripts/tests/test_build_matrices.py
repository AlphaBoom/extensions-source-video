import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), SCRIPTS / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildMatrixTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        self.module = load_script('generate-build-matrices')
        self.write('src/en/direct/build.gradle.kts', 'dependencies { implementation(project(":lib:middle")) }')
        self.write('src/zh/themed/build.gradle.kts', 'videoExtension { theme = "theme" }')
        self.write('lib/leaf/build.gradle.kts', '')
        self.write('lib/middle/build.gradle.kts', 'dependencies { api(project(":lib:leaf")) }')
        self.write('lib-multisrc/theme/build.gradle.kts', 'dependencies { api(project(":lib:middle")) }')

    def tearDown(self):
        os.chdir(self.previous)
        self.temp.cleanup()

    def write(self, name, content):
        file = Path(name)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def changed(self, diff):
        with patch.object(self.module, 'run_command', return_value=diff):
            return self.module.get_module_list('base')

    def test_transitive_library_and_kotlin_theme_consumers(self):
        modules, delete = self.changed('M\tlib/leaf/src/Extractor.kt')
        self.assertEqual(modules, [':src:en:direct', ':src:zh:themed'])
        self.assertEqual(delete, ['en.direct', 'zh.themed'])

    def test_library_change_without_multisrc_directory(self):
        Path('lib-multisrc/theme/build.gradle.kts').unlink()
        Path('lib-multisrc/theme').rmdir()
        Path('lib-multisrc').rmdir()
        self.assertEqual(self.changed('M\tlib/leaf/src/Extractor.kt'), ([':src:en:direct'], ['en.direct']))

    def test_included_build_and_manifest_rebuild_every_extension(self):
        for file in ['gradle/build-logic/src/main/kotlin/VideoExtensionPlugin.kt', 'common/AndroidManifest.xml']:
            with self.subTest(file=file):
                self.assertEqual(self.changed(f'M\t{file}')[0], [':src:en:direct', ':src:zh:themed'])

    def test_groovy_to_kotlin_rename_keeps_module(self):
        self.assertEqual(self.changed('R100\tsrc/en/direct/build.gradle\tsrc/en/direct/build.gradle.kts'), ([':src:en:direct'], ['en.direct']))

    def test_kotlin_version_bumps_cover_extensions_and_themes(self):
        bump = load_script('bump-versions')
        source = 'versionCode = 17\nextra["baseVersionCode"] = 4\nextVersionCode = 2\n'
        self.assertEqual(bump.VERSION_REGEX.sub(bump.replace_version, source), 'versionCode = 18\nextra["baseVersionCode"] = 5\nextVersionCode = 3\n')
        self.assertIn(Path('src/zh/themed/build.gradle.kts'), bump.find_files_with_match('theme = "theme"', include_multisrc=False))
