"""Local HTTP CONNECT proxy that pins DNS results and refuses non-public destinations.

Used only by the extractor; defense in depth still requires OS-level egress isolation.
"""
import ipaddress
import select
import socket
import socketserver
import threading
from urllib.parse import urlsplit


def public_ip(address):
    ip = ipaddress.ip_address(address)
    return ip.is_global and not ip.is_multicast and not ip.is_unspecified


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(15)
        line = self.rfile.readline(8193).decode("ascii", "strict").strip()
        parts = line.split(" ")
        if len(parts) != 3 or parts[0] != "CONNECT":
            self.wfile.write(b"HTTP/1.1 403 Forbidden\r\n\r\n")
            return
        target = urlsplit("//" + parts[1])
        if not target.hostname or target.port != 443 or target.username:
            self.wfile.write(b"HTTP/1.1 403 Forbidden\r\n\r\n")
            return
        while self.rfile.readline(8193) not in (b"\r\n", b"\n", b""):
            pass
        answers = socket.getaddrinfo(target.hostname, 443, type=socket.SOCK_STREAM)
        # Refuse mixed DNS responses rather than selecting a potentially rebinding answer.
        if not answers or any(not public_ip(a[4][0]) for a in answers):
            self.wfile.write(b"HTTP/1.1 403 Forbidden\r\n\r\n")
            return
        upstream = socket.create_connection(answers[0][4], timeout=15)
        try:
            self.wfile.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
            self.wfile.flush()
            sockets = [self.connection, upstream]
            while True:
                ready, _, _ = select.select(sockets, [], [], 60)
                if not ready:
                    break
                for sock in ready:
                    data = sock.recv(65536)
                    if not data:
                        return
                    (upstream if sock is self.connection else self.connection).sendall(data)
        finally:
            upstream.close()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class ForwardHandler(socketserver.BaseRequestHandler):
    def handle(self):
        with socket.create_connection(("api", 8765), timeout=10) as upstream:
            sockets = [self.request, upstream]
            while True:
                ready, _, _ = select.select(sockets, [], [], 120)
                if not ready:
                    return
                for sock in ready:
                    data = sock.recv(65536)
                    if not data:
                        return
                    (upstream if sock is self.request else self.request).sendall(data)


def start(host="127.0.0.1", port=0):
    server = Server((host, port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


if __name__ == "__main__":
    forward = Server(("0.0.0.0", 8766), ForwardHandler)
    threading.Thread(target=forward.serve_forever, daemon=True).start()
    server = Server(("0.0.0.0", 8888), Handler)
    server.serve_forever()
