"""Reconcile exact viewer HTTPRoutes; existing ExternalDNS creates their DNS.

Runs as a separate, unprivileged sidecar. No provider keys, owner tokens, health
DB queries, wildcard routes, or cluster-wide Kubernetes permissions are needed.
"""
from __future__ import annotations

import json
import os
import re
import ssl
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

from .connection_domains import DNS_LABEL, DOMAIN

OWNER_KEY = 'juggluco.app/managed-by'
OWNER_VALUE = 'personal-dns'
ROUTE_PREFIX = 'juggluco-personal-'


def desired_routes(labels, domain, namespace, gateway_namespace, gateway, section):
    if not DOMAIN.fullmatch(domain) or len(domain) > 190:
        raise ValueError('invalid viewer domain')
    if not isinstance(labels, list) or any(not isinstance(label, str) or not DNS_LABEL.fullmatch(label) for label in labels):
        raise ValueError('invalid public DNS label snapshot')
    hosts = sorted({label + '.' + domain for label in labels})
    result = []
    for start in range(0, len(hosts), 16):
        result.append({'apiVersion': 'gateway.networking.k8s.io/v1', 'kind': 'HTTPRoute',
            'metadata': {'name': ROUTE_PREFIX + str(start // 16), 'namespace': namespace,
                         'labels': {OWNER_KEY: OWNER_VALUE}},
            'spec': {'parentRefs': [{'group': 'gateway.networking.k8s.io', 'kind': 'Gateway',
                'name': gateway, 'namespace': gateway_namespace, 'sectionName': section}],
                'hostnames': hosts[start:start + 16],
                'rules': [{'matches': [{'path': {'type': 'PathPrefix', 'value': '/'}}],
                           'backendRefs': [{'group': '', 'kind': 'Service', 'name': 'juggluco', 'port': 80}]}]}})
    return result


class KubernetesRoutes:
    def __init__(self, namespace, credential_path):
        self.credentials = Path(credential_path)
        self.client = httpx.Client(base_url='https://kubernetes.default.svc',
            verify=ssl.create_default_context(cafile=str(self.credentials / 'ca.crt')),
            trust_env=False, timeout=15)
        self.path = '/apis/gateway.networking.k8s.io/v1/namespaces/' + namespace + '/httproutes'

    def request(self, method, path, **kwargs):
        # Projected service-account tokens rotate; never cache one permanently.
        token = (self.credentials / 'token').read_text().strip()
        response = self.client.request(method, path, headers={'Authorization': 'Bearer ' + token}, **kwargs)
        response.raise_for_status()
        return response.json()

    def list(self):
        return self.request('GET', self.path, params={'labelSelector': OWNER_KEY + '=' + OWNER_VALUE})['items']

    def create(self, route):
        self.request('POST', self.path, json=route)

    def replace(self, route, previous):
        route['metadata']['resourceVersion'] = previous['metadata']['resourceVersion']
        self.request('PUT', self.path + '/' + route['metadata']['name'], json=route)

    def delete(self, previous):
        # Preconditions prevent deleting a replacement created by somebody else.
        self.request('DELETE', self.path + '/' + previous['metadata']['name'],
            json={'apiVersion': 'v1', 'kind': 'DeleteOptions',
                  'preconditions': {'uid': previous['metadata']['uid'], 'resourceVersion': previous['metadata']['resourceVersion']}})


def reconcile(api, desired):
    current = {item['metadata']['name']: item for item in api.list()
               if item['metadata'].get('labels', {}).get(OWNER_KEY) == OWNER_VALUE
               and re.fullmatch(ROUTE_PREFIX + r'\d+', item['metadata']['name'])}
    keep = set()
    # Add/replace first. Old domains are already revoked by the backend even if
    # DNS takes another minute to disappear; no data remains on a removed name.
    for route in desired:
        name = route['metadata']['name']
        keep.add(name)
        previous = current.get(name)
        if previous is None:
            api.create(route)  # 409 on an unowned name: do not take it over.
        elif previous['spec'] != route['spec']:
            api.replace(route, previous)
    for name, previous in current.items():
        if name not in keep:
            api.delete(previous)


def public_ready(client, hostname):
    try:
        response = client.get('https://' + hostname + '/v1/viewer/ready')
        body = response.json()
        return response.status_code == 200 and body.get('ready') is True and body.get('host') == hostname
    except (httpx.HTTPError, ValueError, AttributeError):
        return False


def publish_status(path, hosts):
    path.parent.mkdir(parents=True, exist_ok=True)
    name = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as output:
            name = output.name
            json.dump({'checked_at': time.time(), 'ready_hosts': sorted(hosts)}, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        if name and os.path.exists(name):
            os.unlink(name)


def main():
    namespace = os.environ['POD_NAMESPACE']
    domain = os.environ['VIEWER_DOMAIN']
    snapshot = Path(os.environ['DOMAIN_SNAPSHOT_PATH'])
    status = Path(os.environ['DNS_STATUS_PATH'])
    api = KubernetesRoutes(namespace, '/var/run/secrets/juggluco-controller')
    probes = httpx.Client(timeout=5, follow_redirects=False, trust_env=False)
    verified = {}
    previous_summary = None
    while True:
        try:
            # Missing/malformed input must NOT delete existing DNS records.
            state = json.loads(snapshot.read_text(encoding='utf-8'))
            if state.get('schema') != 1:
                raise ValueError('unsupported DNS snapshot')
            desired = desired_routes(state['labels'], domain, namespace,
                os.environ['GATEWAY_NAMESPACE'], os.environ['GATEWAY_NAME'], os.environ['GATEWAY_SECTION'])
            reconcile(api, desired)
            hosts = {host for route in desired for host in route['spec']['hostnames']}
            now = time.time()
            verified = {host: checked for host, checked in verified.items() if host in hosts and now - checked < 60}
            pending = sorted(hosts - verified.keys())
            with ThreadPoolExecutor(max_workers=8) as workers:
                for host, ok in zip(pending, workers.map(lambda host: public_ready(probes, host), pending)):
                    if ok:
                        verified[host] = time.time()
            publish_status(status, verified)
            summary = {'addresses': len(hosts), 'ready': len(verified)}
            if summary != previous_summary:
                print(json.dumps(summary), flush=True)
                previous_summary = summary
        except Exception as error:
            # Never print tokens, private viewer names, provider bodies or URLs.
            print(json.dumps({'dns_reconcile': 'retry', 'error_type': type(error).__name__}), flush=True)
        time.sleep(10)


if __name__ == '__main__':
    main()
