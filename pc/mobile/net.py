"""This computer's own addresses, read from its network cards (nobody is asked)."""
from __future__ import annotations

import ipaddress
import socket

import psutil

VIRTUAL = ipaddress.ip_network("198.18.0.0/15")       # proxy TUN cards (Mihomo, Clash) use it
GLOBAL6 = ipaddress.ip_network("2000::/3")
PORT = 18766


def addr(host: str, port: int) -> str:
    return f"[{host}]:{port}" if ":" in host else f"{host}:{port}"


def parse(text: str) -> tuple[str, int]:
    host, _, port = text.rpartition(":")
    return host.strip("[]"), int(port)


def _up() -> list[tuple[str, ipaddress._BaseAddress]]:
    stats = psutil.net_if_stats()
    found = []
    for name, entries in psutil.net_if_addrs().items():
        if not getattr(stats.get(name), "isup", False):
            continue
        for entry in entries:
            if entry.family in (socket.AF_INET, socket.AF_INET6):
                found.append((name, ipaddress.ip_address(entry.address.split("%")[0])))
    return found


def lan_ipv4() -> list[str]:
    return sorted({str(ip) for _, ip in _up() if ip.version == 4 and ip.is_private and not ip.is_loopback
                   and not ip.is_link_local and ip not in VIRTUAL})


def global_ipv6() -> list[str]:
    return sorted({str(ip) for _, ip in _up() if ip.version == 6 and ip in GLOBAL6})
