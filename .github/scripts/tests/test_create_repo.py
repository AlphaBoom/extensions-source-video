import json
import os
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / 'create-repo.py'


class RepoIndexTests(unittest.TestCase):
    def test_index_reads_metadata_inside_apk_and_retains_factory_ids(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'sdk/build-tools/37.0.0').mkdir(parents=True)
            apk_dir = root / 'repo/apk'
            apk_dir.mkdir(parents=True)
            sources = [
                {'id': '1234567890123456789', 'name': 'Factory source', 'lang': lang, 'baseUrl': 'https://example.com'}
                for lang in ['all', 'zh', 'en']
            ]
            apk_name = 'aniyomi-all.example-v17.18.apk'
            with ZipFile(apk_dir / apk_name, 'w') as archive:
                archive.writestr('res/mipmap/icon.png', b'icon')
                archive.writestr('assets/extension-sources.json', json.dumps(sources))
            badging = """package: name='eu.kanade.tachiyomi.animeextension.all.example' versionCode='17018' versionName='17.18'
application-label:'Aniyomi: Example'
application-icon-320:'res/mipmap/icon.png'
meta-data: name='tachiyomi.animeextension.nsfw' value='1'
"""
            with patch.dict(os.environ, {'ANDROID_HOME': str(root / 'sdk'), 'REPO_DIR': str(root / 'repo')}), patch('subprocess.check_output', return_value=badging.encode()):
                runpy.run_path(str(SCRIPT))
            index = json.loads((root / 'repo/index.min.json').read_text())
            self.assertEqual(index[0]['sources'], sources)
            self.assertEqual(index[0]['code'], 17018)
            self.assertEqual(index[0]['version'], '17.18')
            self.assertEqual(index[0]['nsfw'], 1)
            self.assertTrue((root / 'repo/icon/eu.kanade.tachiyomi.animeextension.all.example.png').exists())

    def test_missing_metadata_stops_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'sdk/build-tools/37.0.0').mkdir(parents=True)
            (root / 'repo/apk').mkdir(parents=True)
            with ZipFile(root / 'repo/apk/aniyomi-en.example-v17.1.apk', 'w') as archive:
                archive.writestr('res/mipmap/icon.png', b'icon')
            badging = """package: name='example' versionCode='17001' versionName='17.1'
application-label:'Example'
application-icon-320:'res/mipmap/icon.png'
meta-data: name='tachiyomi.animeextension.nsfw' value='0'
"""
            with patch.dict(os.environ, {'ANDROID_HOME': str(root / 'sdk'), 'REPO_DIR': str(root / 'repo')}), patch('subprocess.check_output', return_value=badging.encode()), self.assertRaises(KeyError):
                runpy.run_path(str(SCRIPT))
            self.assertFalse((root / 'repo/index.min.json').exists())
