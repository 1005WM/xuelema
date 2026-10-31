"""Package source only, excluding SDKs, credentials, caches and build output."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / 'dist'
DIST.mkdir(exist_ok=True)
files = [ROOT / p for p in ['.gitignore','README.md','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat']]
for folder in ['app','gradle','docs','tools']:
    for path in (ROOT / folder).rglob('*'):
        if path.is_file() and not any(part in {'build','node_modules','__pycache__'} for part in path.relative_to(ROOT).parts):
            files.append(path)
archive = DIST / 'HomeworkAssistant-source.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as out:
    for path in sorted(set(files)):
        name = path.relative_to(ROOT).as_posix()
        info = zipfile.ZipInfo('HomeworkAssistant/' + name, date_time=(2026,10,28,0,0,0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = ((0o100755 if name == 'gradlew' else 0o100644) << 16)
        out.writestr(info,path.read_bytes())
print(archive)
print('Source files:', len(files))
