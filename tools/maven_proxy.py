"""Optional local build cache for hosts whose JVM cannot reach Maven over TLS.
Upstream HTTPS certificates remain verified. Bind loopback only; never use for app traffic.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import urlopen
from urllib.error import HTTPError
import hashlib
import time
import threading

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.tools/maven-cache'
CACHE.mkdir(parents=True, exist_ok=True)
UPSTREAM = {'google':'https://dl.google.com/dl/android/maven2/', 'central':'https://repo.maven.apache.org/maven2/', 'plugins':'https://plugins.gradle.org/m2/'}
DOWNLOADS = threading.Semaphore(3)
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_HEAD(self): self.handle_request(False)
    def do_GET(self): self.handle_request(True)
    def handle_request(self, body):
        parts = self.path.lstrip('/').split('/', 1)
        if len(parts) != 2 or parts[0] not in UPSTREAM or '..' in parts[1]:
            self.send_error(404); return
        target = UPSTREAM[parts[0]] + parts[1]
        file = CACHE / hashlib.sha256(target.encode()).hexdigest()
        try:
            if file.exists(): data = file.read_bytes()
            else:
                with DOWNLOADS:
                    for attempt in range(6):
                        try:
                            with urlopen(target, timeout=90) as response: data = response.read()
                            break
                        except HTTPError: raise
                        except Exception:
                            if attempt == 5: raise
                            time.sleep(1 + attempt)
                file.write_bytes(data)
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            if body: self.wfile.write(data)
        except HTTPError as e: self.send_error(e.code)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass
        except Exception as e:
            print('Upstream error', target, type(e).__name__, flush=True)
            try: self.send_error(502)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass

(ROOT / '.tools/network.init.gradle').write_text('''settingsEvaluated { settings ->
    settings.pluginManagement.repositories.clear()
    ['google', 'central', 'plugins'].each { name ->
        settings.pluginManagement.repositories.maven { url = uri('http://127.0.0.1:18742/' + name); allowInsecureProtocol = true }
    }
    settings.dependencyResolutionManagement.repositories.clear()
    ['google', 'central'].each { name ->
        settings.dependencyResolutionManagement.repositories.maven { url = uri('http://127.0.0.1:18742/' + name); allowInsecureProtocol = true }
    }
}
''', encoding='utf-8')
print('Verified HTTPS Maven cache listening on 127.0.0.1:18742', flush=True)
ThreadingHTTPServer(('127.0.0.1',18742), Handler).serve_forever()
