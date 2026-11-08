#!/usr/bin/env python3
"""A read-only HTTP server for the device test: GET with Range / If-Range / ETag, as the CDN answers.

  python scripts/serve_model.py --port 8765 /m=<transport folder> /audio=<pcm folder> /conformance=<folder>

Each PREFIX=DIR maps URL paths under PREFIX to files under DIR (no listing, no path outside DIR).
ETag = the file's size and mtime. With `adb reverse tcp:8765 tcp:8765` the phone reaches it at
http://127.0.0.1:8765/. Loopback only.
"""
import argparse
import email.utils
import http.server
import os
import re
import socketserver


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("roots", nargs="+", help="PREFIX=DIR")
    a = ap.parse_args()
    roots = []
    for r in a.roots:
        p, d = r.split("=", 1)
        roots.append(("/" + p.strip("/") + "/", os.path.realpath(d)))

    class H(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def resolve(self):
            path = self.path.split("?", 1)[0]
            for prefix, d in roots:
                if path.startswith(prefix):
                    f = os.path.realpath(os.path.join(d, path[len(prefix):]))
                    if f.startswith(d + os.sep) and os.path.isfile(f):
                        return f
            return None

        def do_GET(self):
            f = self.resolve()
            if not f:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            st = os.stat(f)
            size = st.st_size
            etag = '"%x-%x"' % (size, int(st.st_mtime))
            start = 0
            rng = self.headers.get("Range")
            if_range = self.headers.get("If-Range")
            m = re.match(r"bytes=(\d+)-$", rng or "")
            partial = m is not None and (if_range is None or if_range == etag) and int(m.group(1)) < size
            if partial:
                start = int(m.group(1))
                self.send_response(206)
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, size - 1, size))
            else:
                self.send_response(200)
            self.send_header("Content-Length", str(size - start))
            self.send_header("ETag", etag)
            self.send_header("Last-Modified", email.utils.formatdate(st.st_mtime, usegmt=True))
            self.send_header("Content-Type", "application/octet-stream")
            self.end_headers()
            with open(f, "rb") as fh:
                fh.seek(start)
                while True:
                    b = fh.read(1 << 20)
                    if not b:
                        break
                    try:
                        self.wfile.write(b)
                    except (BrokenPipeError, ConnectionResetError):
                        return

        def log_message(self, fmt, *args):
            print("%s %s" % (self.requestline, self.headers.get("Range") or ""), flush=True)

    class S(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True

    S(("127.0.0.1", a.port), H).serve_forever()


if __name__ == "__main__":
    main()
