import asyncio
import copy
import json
import secrets
import sqlite3
import time
from uuid import uuid4
from urllib.parse import urlparse

import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.connections import ConnectionRegistry, digest
from app.connection_domains import DNS_LABEL, domain_label, compact_domain_label, domain_ready
from app.connection_dns_controller import desired_routes, reconcile, public_ready, publish_status, OWNER_KEY, OWNER_VALUE
from conftest import make_settings, TEST_TOKEN
from test_connections import register, viewer
from test_forecast import reading_batch
from test_viewer_stream import _stream_scope, _body


def settings(tmp_path):
    dist = tmp_path / 'pwa'
    dist.mkdir(exist_ok=True)
    (dist / 'index.html').write_text('<!doctype html><html>personal app</html>')
    (dist / 'sw.js').write_text('// test worker')
    return make_settings(tmp_path, allowed_hosts=('testserver', '*.example.com'),
        connection_viewer_domain='example.com', pwa_dist_path=dist)


def test_dns_labels_are_stable_unpredictable_and_migrate_without_changing_owners(tmp_path):
    handle = 'my-sugar-' + secrets.token_urlsafe(16)
    label = domain_label(handle)
    assert DNS_LABEL.fullmatch(label)
    assert label.startswith('gluco-my-sugar-')
    assert label == domain_label(handle)
    assert label != domain_label(secrets.token_urlsafe(16))
    assert len(domain_label('x' * 24 + '-' + secrets.token_urlsafe(16))) <= 63
    registry_path = tmp_path / 'old.sqlite3'
    identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
    with sqlite3.connect(registry_path) as old:
        old.execute('CREATE TABLE installations (id TEXT PRIMARY KEY, owner_hash TEXT, handle TEXT, created_at INTEGER, legacy INTEGER, rotation_id TEXT, ai_day INTEGER DEFAULT 0, ai_requests INTEGER DEFAULT 0)')
        old.execute('INSERT INTO installations(id,owner_hash,handle,created_at,legacy) VALUES (?,?,?,?,1)',
                    (identifier, digest(secret), handle, 123))
    registry = ConnectionRegistry(registry_path)
    migrated = registry.authenticate(identifier, secret)
    assert migrated['handle'] == handle and migrated['legacy'] == 1
    assert migrated['dns_label'] == label
    assert registry.get_domain(label)['id'] == identifier
    compact = migrated['compact_dns_label']
    assert compact.startswith('g-my-sugar-') and len(compact.rsplit('-', 1)[1]) == 12
    assert DNS_LABEL.fullmatch(compact)
    assert registry.get_domain(compact)['id'] == identifier
    assert registry.authenticate(identifier, secret)['compact_dns_label'] == compact
    assert migrated['created_at'] == 123


def test_host_selects_only_its_data_root_pwa_and_manifest_and_blocks_write_routes(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        a, ah = register(client)
        b, bh = register(client)
        assert a['viewer_url'].startswith('https://gluco-')
        assert a['viewer_url'].endswith('.example.com/')
        assert a['viewer_ready'] is False
        batch = reading_batch(int(time.time() * 1000))
        client.post(a['api_path'] + '/v1/glucose/readings', headers=ah, json=batch).raise_for_status()
        url = a['viewer_url'].rstrip('/')
        other = b['viewer_url'].rstrip('/')
        assert client.get(url + '/v1/viewer/snapshot?profile=invalid').json()['current_glucose'] is not None
        assert client.get(other + '/v1/viewer/snapshot').json()['current_glucose'] is None
        page = client.get(url + '/', follow_redirects=False)
        assert page.status_code == 200 and 'personal app' in page.text
        assert 'location' not in page.headers
        assert "default-src 'self'" in page.headers['content-security-policy']
        assert page.headers['referrer-policy'] == 'no-referrer'
        assert page.headers['x-robots-tag'].startswith('noindex')
        worker = client.get(url + '/viewer/sw.js')
        assert worker.headers['service-worker-allowed'] == '/'
        manifest = client.get(url + '/viewer/personal.webmanifest?profile=other').json()
        assert manifest['id'] == manifest['start_url'] == manifest['scope'] == '/'
        assert client.get(url + '/v1/viewer/session').json()['access_mode'] == 'public'
        assert client.get(url + '/v1/viewer/ready').json()['host'] == urlparse(url).hostname
        for path in (a['api_path'] + '/v1/forecast/current', '/v1/intakes', viewer(b), '/v1/ready'):
            assert client.get(url + path, headers=ah).status_code == 404
        assert client.post(url + '/v1/glucose/readings', headers=ah, json=batch).status_code == 405
        assert client.get('https://unknown.example.com/v1/viewer/snapshot').status_code == 401
        assert client.get('https://gluco-invalid.example.com/').status_code == 401
        assert client.get(url.replace('https:', 'http:') + '/').status_code == 426
        # Spoofed forwarding headers never select another profile or URL zone.
        described = client.get(a['api_path'] + '/connection', headers={**ah, 'X-Forwarded-Host': 'evil.invalid'}).json()
        assert described['viewer_url'] == a['viewer_url']
        snapshot = json.loads((app.state.connections.directory / 'public-routing/viewer-domains.json').read_text())
        assert len(snapshot['labels']) == 4 and 'owner_hash' not in str(snapshot)


def test_compact_alias_preserves_legacy_owner_history_and_revokes_both_on_rotation(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        profile, headers = register(client, claim=True)
        client.post('/v1/glucose/readings', headers={'Authorization': 'Bearer ' + TEST_TOKEN},
                    json=reading_batch(int(time.time() * 1000))).raise_for_status()
        short, old = profile['compact_viewer_url'], profile['viewer_url']
        assert short.startswith('https://g-') and len(short) < len(old)
        assert len(urlparse(short).hostname.split('.')[0]) == 14
        assert profile['compact_viewer_ready'] is False
        for url in (short, old):
            snapshot = client.get(url + 'v1/viewer/snapshot').json()
            assert snapshot['current_glucose'] is not None
            assert client.get(url).status_code == 200
            assert client.get(url + 'viewer/personal.webmanifest').json()['start_url'] == '/'
            assert client.post(url + 'v1/glucose/readings', headers=headers, json={}).status_code == 405
        described = client.get(profile['api_path'] + '/connection', headers=headers).json()
        assert described == profile  # Stable aliases; never re-register or re-claim.
        publish_status(app.state.connections.directory / 'dns-status.json', [urlparse(short).hostname])
        described = client.get(profile['api_path'] + '/connection', headers=headers).json()
        assert described['compact_viewer_ready'] is True and described['viewer_ready'] is False
        other, _ = register(client)
        assert client.get(other['compact_viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is None
        operation = {'operation_id': str(uuid4()), 'label': 'vlad'}
        rotated = client.post(profile['api_path'] + '/connection', headers=headers, json=operation).json()
        assert rotated['compact_viewer_url'].startswith('https://g-vlad-')
        assert rotated['api_path'] == profile['api_path'] and rotated['installation_id'] == profile['installation_id']
        assert rotated == client.post(profile['api_path'] + '/connection', headers=headers, json=operation).json()
        for url in (short, old):
            assert client.get(url + 'v1/viewer/snapshot').status_code == 401
        assert client.get(rotated['compact_viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(profile['api_path'] + '/v1/intakes', headers=headers).status_code == 200


def test_compact_collisions_retry_and_never_replace_an_existing_profile(tmp_path, monkeypatch):
    registry = ConnectionRegistry(tmp_path / 'registry.sqlite3')
    first = registry.register(str(uuid4()), secrets.token_urlsafe(32))
    candidate = compact_domain_label('a' * 22)
    labels = iter([first['compact_dns_label'], first['compact_dns_label'], candidate])
    monkeypatch.setattr('app.connections.compact_domain_label', lambda handle: next(labels))
    identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
    second = registry.register(identifier, secret)
    assert second['compact_dns_label'] == candidate
    assert registry.get_domain(first['compact_dns_label'])['id'] == first['id']
    assert registry.register(identifier, secret)['compact_dns_label'] == candidate
    monkeypatch.setattr('app.connections.compact_domain_label', lambda handle: candidate)
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as error:
        registry.register(str(uuid4()), secrets.token_urlsafe(32))
    assert error.value.status_code == 503
    with registry.connect() as connection:
        assert connection.execute('SELECT COUNT(*) FROM installations').fetchone()[0] == 2


def test_thousand_compact_profiles_have_unique_durable_isolated_addresses(tmp_path):
    registry = ConnectionRegistry(tmp_path / 'registry.sqlite3', daily_limit=1100)
    owners = {}
    for _ in range(1000):
        identifier = str(uuid4())
        row = registry.register(identifier, secrets.token_urlsafe(32))
        label = row['compact_dns_label']
        assert DNS_LABEL.fullmatch(label) and len(label) == 14
        assert label not in owners
        owners[label] = identifier
    reopened = ConnectionRegistry(registry.path)
    for label, identifier in owners.items():
        assert reopened.get_domain(label)['id'] == identifier


def test_dns_waits_for_https_probe_and_rotation_revokes_old_host_but_not_history(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        a, ah = register(client, claim=True)
        client.post('/v1/glucose/readings', headers={'Authorization': 'Bearer ' + TEST_TOKEN}, json=reading_batch(int(time.time() * 1000))).raise_for_status()
        host = urlparse(a['viewer_url']).hostname
        status_path = app.state.connections.directory / 'dns-status.json'
        assert not domain_ready(status_path, host)
        publish_status(status_path, [host])
        assert client.get(a['api_path'] + '/connection', headers=ah).json()['viewer_ready'] is True
        operation = {'operation_id': str(uuid4()), 'label': 'my-sugar'}
        changed = client.post(a['api_path'] + '/connection', headers=ah, json=operation).json()
        assert changed['viewer_ready'] is False
        assert changed['viewer_url'].startswith('https://gluco-my-sugar-')
        assert changed == client.post(a['api_path'] + '/connection', headers=ah, json=operation).json()
        assert client.get(a['viewer_url'] + 'v1/viewer/snapshot').status_code == 401
        assert client.get(changed['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(viewer(changed)).json()['current_glucose'] is not None
        status_path.write_text(json.dumps({'checked_at': time.time() - 200, 'ready_hosts': [host]}))
        assert not domain_ready(status_path, host)


@pytest.mark.parametrize('label_field', ['dns_label', 'compact_dns_label'])
def test_live_host_stream_is_revoked_even_before_dns_removal(tmp_path, label_field):
    async def exercise():
        app = create_app(settings(tmp_path))
        manager = app.state.connections
        identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
        row = manager.registry.register(identifier, secret)
        scope = _stream_scope([])
        scope['headers'] = [(b'host', (row[label_field] + '.example.com').encode())]
        sent = []
        delivered = False
        never = asyncio.Event()
        async def receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {'type': 'http.request', 'body': b'', 'more_body': False}
            await never.wait()
            return {'type': 'http.disconnect'}
        async def send(message):
            sent.append(message)
            if b'event: ready' in message.get('body', b''):
                manager.registry.rotate(identifier, secret, str(uuid4()), 'changed')
                manager.runtimes[identifier].app.state.glucose_updates.publish_threadsafe(99, 12345)
        async with app.router.lifespan_context(app):
            await asyncio.wait_for(app(scope, receive, send), timeout=3)
        assert 'event: ready' in _body(sent)
        assert 'event: glucose' not in _body(sent)
    asyncio.run(exercise())


def test_controller_batches_exact_routes_and_never_touches_unowned_routes():
    labels = [domain_label(secrets.token_urlsafe(16)) for _ in range(17)]
    labels += [compact_domain_label(secrets.token_urlsafe(16)) for _ in range(16)]
    routes = desired_routes(labels, 'example.com', 'juggluco', 'networking', 'public', 'https')
    assert [len(route['spec']['hostnames']) for route in routes] == [16, 16, 1]
    assert not any('*' in host for route in routes for host in route['spec']['hostnames'])
    class FakeApi:
        def __init__(self):
            self.items = []
            self.operations = []
        def list(self): return self.items
        def create(self, route):
            self.operations.append(('create', route['metadata']['name']))
            self.items.append(copy.deepcopy(route))
        def replace(self, route, old):
            self.operations.append(('replace', route['metadata']['name']))
            self.items[self.items.index(old)] = copy.deepcopy(route)
        def delete(self, old):
            self.operations.append(('delete', old['metadata']['name']))
            self.items.remove(old)
    api = FakeApi()
    reconcile(api, routes)
    assert len(api.operations) == 3
    reconcile(api, routes)
    assert len(api.operations) == 3
    api.items.append({'metadata': {'name': 'unrelated', 'labels': {OWNER_KEY: OWNER_VALUE}}, 'spec': {}})
    reconcile(api, routes[:1])
    assert [name for operation, name in api.operations if operation == 'delete'] == ['juggluco-personal-1', 'juggluco-personal-2']
    assert any(item['metadata']['name'] == 'unrelated' for item in api.items)
    for malicious in [['*.example.com'], ['../escape'], ['UPPERCASE'], 'not-a-list']:
        with pytest.raises(ValueError): desired_routes(malicious, 'example.com', 'ns', 'ns', 'public', 'https')


def test_readiness_probe_requires_matching_host_and_valid_success():
    host = domain_label(secrets.token_urlsafe(16)) + '.example.com'
    for status, body, expected in [(200, {'ready': True, 'host': host}, True),
                                  (200, {'ready': True, 'host': 'other.example.com'}, False),
                                  (503, {'ready': True, 'host': host}, False)]:
        with httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(status, json=body))) as client:
            assert public_ready(client, host) is expected


def test_personal_dns_chart_limits_token_and_storage_to_sidecar():
    from test_forecast_chart import _helm, CHART
    rendered = _helm('template', 'juggluco', str(CHART), '--namespace', 'juggluco')
    assert 'kind: Role\n' in rendered and 'kind: ClusterRole\n' not in rendered
    assert 'resources: [httproutes]' in rendered
    assert 'app.connection_dns_controller' in rendered
    assert 'subPath: "juggluco-installations/public-routing"' in rendered
    assert 'automountServiceAccountToken: false' in rendered
    assert rendered.count('mountPath: /var/run/secrets/juggluco-controller') == 1
    disabled = _helm('template', 'juggluco', str(CHART), '--namespace', 'juggluco', '--set', 'personalDns.enabled=false')
    assert 'app.connection_dns_controller' not in disabled
    assert 'JUGGLUCO_CONNECTION_VIEWER_DOMAIN' not in disabled
