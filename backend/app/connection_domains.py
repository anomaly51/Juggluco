"""DNS-safe, stable viewer capabilities; never derive URLs from request headers."""
import base64
import hashlib
import json
import re
import time

DNS_LABEL = re.compile(r"^gluco-(?:[a-z0-9][a-z0-9-]{0,23}-)?[a-z2-7]{26}$")
DOMAIN = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")


def domain_label(handle: str) -> str:
    # Retain 128 bits of entropy, with no case-sensitive/base64 DNS characters.
    suffix = base64.b32encode(hashlib.sha256(handle.encode('ascii')).digest()[:16]).decode('ascii').lower().rstrip('=')
    prefix = handle[:-23] + '-' if len(handle) > 22 else ''
    return 'gluco-' + prefix + suffix


def is_personal_hostname(host: str | None, settings) -> bool:
    domain = settings.connection_viewer_domain
    # Explicit API hosts remain API hosts. Every other admitted host in this zone
    # must fail closed as a viewer, never expose the legacy owner's public data.
    return bool(domain and host and host.lower().endswith('.' + domain)
                and host.lower() not in settings.allowed_hosts)


def domain_ready(path, hostname: str) -> bool:
    try:
        status = json.loads(path.read_text(encoding='utf-8'))
        age = time.time() - status['checked_at']
        return 0 <= age < 180 and hostname in status['ready_hosts']
    except (OSError, ValueError, KeyError, TypeError):
        return False
