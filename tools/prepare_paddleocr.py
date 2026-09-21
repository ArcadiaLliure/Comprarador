#!/usr/bin/env python3
"""Prepara el SDK Android de PaddleOCR i els models locals per compilar Comprarador.

L'SDK es copia des d'una revisió fixa del repositori original, preservant-ne
l'autoria i la llicència. Els pesos no es versionen: es baixen en compilar,
s'incorporen als assets de l'APK i se'n registra la suma SHA-256.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / 'ppocr-sdk'
UPSTREAM = 'dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf'
MODEL_BASE = ('https://paddle-model-ecology.bj.bcebos.com/paddlex/'
              'official_inference_model/paddle3.0.0/')
MODELS = {
    'det': ('PP-OCRv6_small_det_onnx_infer.tar', ('inference.onnx',)),
    'rec': ('PP-OCRv6_small_rec_onnx_infer.tar', ('inference.onnx', 'inference.yml')),
}
SDK_GRADLE = '''plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false; consumerProguardFiles("proguard-rules.pro") } }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("com.quickbirdstudios:opencv:4.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
'''


def command(*args: str, cwd: Path | None = None) -> bytes:
    return subprocess.check_output(args, cwd=cwd, stderr=subprocess.STDOUT)


def vendor_sdk() -> None:
    source_marker = SDK / 'ORIGEN.md'
    if source_marker.exists() and UPSTREAM in source_marker.read_text(encoding='utf-8'):
        assert (SDK / 'src/main/java/com/paddle/ocr/PaddleOCR.kt').is_file()
        return
    with tempfile.TemporaryDirectory(prefix='paddleocr-sdk-') as temp:
        repo = Path(temp) / 'upstream'
        repo.mkdir()
        command('git', 'init', '-q', str(repo))
        command('git', 'remote', 'add', 'origin', 'https://github.com/PaddlePaddle/PaddleOCR.git', cwd=repo)
        command('git', '-c', 'protocol.version=2', 'fetch', '-q', '--depth=1', '--filter=blob:none',
                'origin', UPSTREAM, cwd=repo)
        command('git', 'sparse-checkout', 'init', '--cone', cwd=repo)
        command('git', 'sparse-checkout', 'set', 'deploy/ppocr-android/ppocr-sdk', cwd=repo)
        command('git', 'checkout', '-q', '--detach', 'FETCH_HEAD', cwd=repo)
        original = repo / 'deploy/ppocr-android/ppocr-sdk'
        assert (original / 'src/main/java/com/paddle/ocr/PaddleOCR.kt').is_file()
        SDK.mkdir(parents=True, exist_ok=True)
        shutil.copytree(original / 'src/main', SDK / 'src/main', dirs_exist_ok=True)
        shutil.copy2(original / 'proguard-rules.pro', SDK / 'proguard-rules.pro')
        (SDK / 'LICENSE-APACHE-2.0').write_bytes(command('git', 'show', f'{UPSTREAM}:LICENSE', cwd=repo))
        (SDK / 'build.gradle.kts').write_text(SDK_GRADLE, encoding='utf-8')
        source_marker.write_text(
            '# Procedència del SDK OCR\n\n'
            f'Codi de PaddlePaddle/PaddleOCR, revisió `{UPSTREAM}`, carpeta '
            '`deploy/ppocr-android/ppocr-sdk`. Llicència Apache 2.0. '
            'Els avisos de copyright originals es conserven. '
            'S\'ha adaptat exclusivament el fitxer Gradle a les versions de Comprarador.\n'
            'Els models PP-OCRv6_small (detecció i reconeixement) també són Apache 2.0.\n',
            encoding='utf-8')
    print(f'SDK PaddleOCR preparat a {SDK} ({UPSTREAM})')


def download(url: str, target: Path) -> None:
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Comprarador-build/0.1.4'}), timeout=180) as stream, target.open('wb') as out:
        shutil.copyfileobj(stream, out)
    assert target.stat().st_size > 20_000, f'Descàrrega incompleta: {url}'


def install_models() -> None:
    assert (SDK / 'src/main/java/com/paddle/ocr/PaddleOCR.kt').is_file(), 'Prepareu abans el SDK'
    folder = SDK / 'src/main/assets/models'
    report: dict[str, object] = {'model': 'PP-OCRv6_small', 'sdk_revision': UPSTREAM, 'files': {}}
    with tempfile.TemporaryDirectory(prefix='paddleocr-models-') as temp:
        for kind, (archive, filenames) in MODELS.items():
            directory = folder / kind
            directory.mkdir(parents=True, exist_ok=True)
            if not all((directory / name).is_file() and (directory / name).stat().st_size > 100 for name in filenames):
                tarpath = Path(temp) / archive
                download(MODEL_BASE + archive, tarpath)
                with tarfile.open(tarpath, 'r:*') as tar:
                    for filename in filenames:
                        matches = [m for m in tar.getmembers() if m.isfile() and Path(m.name).name == filename]
                        if len(matches) != 1:
                            raise ValueError(f'Esperàvem un únic {filename} a {archive}; trobat: {len(matches)}')
                        member = tar.extractfile(matches[0])
                        assert member is not None
                        with member, (directory / filename).open('wb') as out:
                            shutil.copyfileobj(member, out)
            for filename in filenames:
                path = directory / filename
                data_size = path.stat().st_size
                assert data_size >= (100_000 if filename.endswith('.onnx') else 100), path
                digest = hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
                report['files'][f'{kind}/{filename}'] = {'bytes': data_size, 'sha256': digest}
    (folder / 'model-provenance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vendor', action='store_true', help='Copia les fonts del SDK al repositori')
    parser.add_argument('--models', action='store_true', help='Baixa els pesos ONNX que s\'inclouran a l\'APK')
    args = parser.parse_args()
    if not args.vendor and not args.models:
        parser.error('Cal indicar --vendor i/o --models')
    if args.vendor:
        vendor_sdk()
    if args.models:
        install_models()
