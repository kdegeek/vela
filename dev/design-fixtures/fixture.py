#!/usr/bin/env python3
"""Serve the CURRENT phone.html and synthetic phone routes on IPv4 loopback only.

Python 3.10+ standard library. Run with -B to avoid checkout bytecode artifacts.
"""
import argparse
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import signal
import socket
import threading
from urllib.parse import urlsplit

from scenarios import SCENARIOS, TOKEN
from store import Store
from validation import MAX_BODY, Refusal, decode, require

PHONE_HTML = Path(__file__).resolve().parents[2] / 'android/app/src/main/assets/phone.html'
MAX_PATH = 2048
READ_TIMEOUT = 3


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, port=8875, scenario='idle'):
        self.store = Store(scenario)
        self.slots = threading.BoundedSemaphore(8)
        # No host option or environment override: binding cannot escape loopback.
        super().__init__(('127.0.0.1', port), Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'  # One bounded request per socket; no pipelining.
    server_version = 'VelaSyntheticFixture/1'
    sys_version = ''

    def setup(self):
        self.request.settimeout(READ_TIMEOUT)
        super().setup()

    def log_message(self, *_):
        pass  # Never echo submitted paths, requests, tokens or values to logs.

    def send_error(self, code, message=None, explain=None):
        self.reply(code, b'Synthetic fixture HTTP request refused.', 'text/plain; charset=utf-8')

    def reply(self, status, data, content_type='application/json; charset=utf-8'):
        self.close_connection = True
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Connection', 'close')
        self.send_header('X-Vela-Fixture', 'synthetic-ram-only')
        self.send_header('Content-Security-Policy', "default-src 'self'; connect-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)

    def boundary(self):
        raw_target = self.requestline.split()[1]
        require(len(raw_target) <= MAX_PATH, 414, 'Fixture path too long.')
        # BaseHTTPRequestHandler normalizes leading // before dispatch; reject the
        # original spelling too so it cannot become an allowed app route.
        require(raw_target == self.path)
        # All supported route IDs are ASCII UUIDs. Reject ambiguous encoding and traversal,
        # absolute-form proxy targets and directory requests instead of normalizing them.
        require(self.path.isascii() and self.path.startswith('/') and not self.path.startswith('//'))
        require(not any(c in self.path for c in ('%', '\\', '#')))
        url = urlsplit(self.path)
        require(not url.scheme and not url.netloc and not any(p in ('.', '..') for p in url.path.split('/')))
        port = self.server.server_port
        hosts = self.headers.get_all('Host', [])
        require(len(hosts) == 1 and hosts[0] in (f'127.0.0.1:{port}', f'localhost:{port}'), 403,
                'Use this fixture through its loopback URL.')
        origins = self.headers.get_all('Origin', [])
        require(not origins or origins == [f'http://{hosts[0]}'], 403, 'Cross-origin fixture request refused.')
        require(self.headers.get('Sec-Fetch-Site') != 'cross-site', 403, 'Cross-site fixture request refused.')
        require(not self.headers.get_all('Transfer-Encoding'), 400, 'Chunked fixture bodies are unsupported; send Content-Length.')
        lengths = self.headers.get_all('Content-Length', [])
        require(len(lengths) <= 1)
        length = 0
        if lengths:
            require(lengths[0].isascii() and lengths[0].isdigit() and len(lengths[0]) <= 6)
            require(lengths[0] == '0' or not lengths[0].startswith('0'))
            length = int(lengths[0])
        require(length <= MAX_BODY, 413, 'Fixture body too large.')
        encodings = self.headers.get_all('Content-Encoding', [])
        require(not encodings or len(encodings) == 1 and encodings[0].lower() == 'identity')
        if url.path != '/':
            tokens = self.headers.get_all('X-Vela-Token', [])
            require(len(tokens) == 1 and hmac.compare_digest(tokens[0].encode('utf-8'), TOKEN.encode()), 401,
                    'Use the PUBLIC synthetic fixture pairing token.')
        return length

    def serve(self):
        try:
            length = self.boundary()
            if self.path == '/' and self.command == 'GET':
                require(length == 0)
                # Fixed asset only. Fresh read on EVERY GET; no DOM/CSS/script patching.
                try:
                    data = PHONE_HTML.read_bytes()
                except OSError:
                    raise Refusal(503, 'Current phone.html asset unavailable.') from None
                self.reply(200, data, 'text/html; charset=utf-8')
                return
            require(self.command in ('GET', 'POST', 'PUT', 'DELETE'), 405, 'Unsupported fixture method.')
            require(self.command != 'GET' or length == 0)
            raw = self.rfile.read(length) if length else b''
            require(len(raw) == length)
            body = decode(raw) if raw else {}
            result = self.server.store.dispatch(self.command, self.path, body)
            self.reply(200, json.dumps(result, ensure_ascii=True, allow_nan=False).encode('utf-8'))
        except Refusal as error:
            self.reply(error.status, str(error).encode('utf-8'), 'text/plain; charset=utf-8')
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
            self.reply(400, b'Invalid synthetic fixture request.', 'text/plain; charset=utf-8')
        except (socket.timeout, TimeoutError):
            self.reply(408, b'Synthetic fixture body read timed out.', 'text/plain; charset=utf-8')
        except (BrokenPipeError, ConnectionResetError):
            pass

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = do_OPTIONS = serve


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8875)
    parser.add_argument('--scenario', choices=SCENARIOS, default='idle')
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error('port must be 0–65535 (0 chooses a free port)')
    if not PHONE_HTML.is_file():
        parser.error('phone.html not found at its fixed repository location')
    server = FixtureServer(args.port, args.scenario)

    def interrupted(_signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    print(f'SYNTHETIC RAM-ONLY phone fixture ({args.scenario}); no devices or providers connected.', flush=True)
    print(f'http://127.0.0.1:{server.server_port}/#t={TOKEN}', flush=True)
    print('Ctrl-C (or SIGTERM) releases this listener and discards all fixture data.', flush=True)
    try:
        server.serve_forever(poll_interval=0.1)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print('Synthetic fixture released.', flush=True)


if __name__ == '__main__':
    main()
