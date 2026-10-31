"""Run the real Android project's pure Kotlin tests without requiring an emulator."""
from pathlib import Path
import os
import subprocess
import urllib.request
import hashlib
import time

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
LIB = TOOLS / 'verification-libs'
LIB.mkdir(parents=True, exist_ok=True)
dependencies = {
    'junit.jar':'junit/junit/4.13.2/junit-4.13.2.jar',
    'hamcrest.jar':'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
    'coroutines.jar':'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar',
}
for name, path in dependencies.items():
    target = LIB / name
    url = 'https://repo.maven.apache.org/maven2/' + path
    if not target.exists(): target.write_bytes(urllib.request.urlopen(url, timeout=90).read())
    sha_file = LIB / (name + '.sha1')
    if not sha_file.exists():
        for attempt in range(4):
            try:
                expected = urllib.request.urlopen(url + '.sha1', timeout=30).read().decode().strip().split()[0]
                sha_file.write_text(expected, encoding='ascii')
                break
            except Exception:
                if attempt == 3: raise
                time.sleep(1)
    expected = sha_file.read_text(encoding='ascii').strip()
    assert hashlib.sha1(target.read_bytes()).hexdigest() == expected, name

gradle = next((TOOLS / 'gradle-home/wrapper/dists').rglob('kotlin-compiler-embeddable-*.jar')).parent
stdlib = next(gradle.glob('kotlin-stdlib-*.jar'))
annotations = next(gradle.glob('annotations-*.jar'))
java_home = os.environ.get('JAVA_HOME')
if not java_home: raise SystemExit('Set JAVA_HOME to JDK 17 or newer first.')
java = str(Path(java_home) / 'bin/java.exe')
out = TOOLS / 'domain-test-classes'
out.mkdir(exist_ok=True)
src = ROOT / 'app/src/main/java/com/homeworkassistant'
test = ROOT / 'app/src/test/java/com/homeworkassistant'
sources = list((src / 'domain/model').glob('*.kt')) + list((src / 'domain/usecase').glob('*.kt'))
sources += [src / p for p in ['data/ai/SseEventReader.kt', 'data/web/TrustedOriginPolicy.kt', 'data/web/HomeworkPageDto.kt', 'data/web/LoginNavigationPolicy.kt', 'data/web/LoginSessionEvaluator.kt', 'data/web/PageOperation.kt']]
sources += list((test / 'domain').glob('*.kt'))
sources += [test / 'data' / name for name in ['SseEventReaderTest.kt','OriginPolicyTest.kt','HomeworkMapperTest.kt','LoginNavigationPolicyTest.kt','LoginSessionEvaluatorTest.kt','PageOperationTest.kt']]
# HTTP/Android-dependent regression suites run via Gradle :app:testDebugUnitTest.
classpath = os.pathsep.join(map(str, [stdlib, annotations] + list(LIB.glob('*.jar'))))
subprocess.run([java, '-cp', str(gradle / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
    '-d', str(out)] + list(map(str,sources)), check=True)
subprocess.run([java,'-cp', str(out) + os.pathsep + classpath,'org.junit.runner.JUnitCore',
    'com.homeworkassistant.domain.AggregateOverviewUseCaseTest',
    'com.homeworkassistant.domain.BuildHomeContentUseCaseTest',
    'com.homeworkassistant.domain.MergePartialSnapshotUseCaseTest',
    'com.homeworkassistant.data.SseEventReaderTest',
    'com.homeworkassistant.data.OriginPolicyTest',
    'com.homeworkassistant.data.HomeworkMapperTest',
    'com.homeworkassistant.data.LoginNavigationPolicyTest',
    'com.homeworkassistant.data.LoginSessionEvaluatorTest',
    'com.homeworkassistant.data.PageOperationTest'],check=True)
