"""DNS-safe, stable viewer capabilities; never derive URLs from request headers."""
import base64
import json
import re
import secrets
import time

DNS_LABEL = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
DOMAIN = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
# This zone is shared with infrastructure. Never publish viewer routes over its
# service names, including the cluster's *-generalN naming convention.
RESERVED_NAMES = frozenset({'www', 'api', 'admin', 'auth', 'login', 'internal', 'argocd',
    'harbor', 'vault', 'keycloak', 'grafana', 'logs', 's3', 'mail', 'smtp', 'imap',
    'ns1', 'ns2', 'status', 'assets', 'cdn', 'juggluco', 'localhost'})


def allowed_viewer_label(label: str) -> bool:
    return bool(DNS_LABEL.fullmatch(label) and label not in RESERVED_NAMES
                and not re.search(r'(?:^|-)general[0-9]+$', label))


def valid_custom_name(label: str) -> bool:
    # Reserve generated names so people cannot impersonate a fresh installation.
    return allowed_viewer_label(label) and not label.startswith('user-')


def domain_label() -> str:
    # 60 random bits, independent of the write credential. The registry checks
    # collisions under its writer lock and stores the result durably.
    suffix = base64.b32encode(secrets.token_bytes(8)).decode('ascii').lower()[:12]
    return 'user-' + suffix


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
