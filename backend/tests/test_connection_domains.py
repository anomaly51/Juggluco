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
from app.connection_domains import DNS_LABEL, domain_label, domain_ready
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


@pytest.mark.parametrize('old_label', [None, 'gluco-my-sugar-' + 'a' * 26, 'g-my-sugar-' + 'a' * 12])
def test_dns_labels_are_short_and_migration_revokes_old_dns_without_changing_owners(tmp_path, old_label):
    handle = 'my-sugar-' + secrets.token_urlsafe(16)
    label = domain_label()
    assert DNS_LABEL.fullmatch(label)
    assert label.startswith('user-') and len(label) == 17
    assert label != domain_label()
    registry_path = tmp_path / 'old.sqlite3'
    identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
    with sqlite3.connect(registry_path) as old:
        old.execute('CREATE TABLE installations (id TEXT PRIMARY KEY, owner_hash TEXT, handle TEXT, created_at INTEGER, legacy INTEGER, rotation_id TEXT, ai_day INTEGER DEFAULT 0, ai_requests INTEGER DEFAULT 0)')
        old.execute('INSERT INTO installations(id,owner_hash,handle,created_at,legacy) VALUES (?,?,?,?,1)',
                    (identifier, digest(secret), handle, 123))
        if old_label:
            old.execute('ALTER TABLE installations ADD COLUMN dns_label TEXT')
            old.execute('UPDATE installations SET dns_label = ?', (old_label,))
    registry = ConnectionRegistry(registry_path)
    migrated = registry.authenticate(identifier, secret)
    assert migrated['handle'] == handle and migrated['legacy'] == 1
    compact = migrated['dns_label']
    assert compact.startswith('user-') and len(compact.rsplit('-', 1)[1]) == 12
    assert DNS_LABEL.fullmatch(compact)
    assert registry.get_domain(compact)['id'] == identifier
    assert registry.authenticate(identifier, secret)['dns_label'] == compact
    if old_label:
        assert registry.get_domain(old_label) is None
    assert migrated['created_at'] == 123


def test_host_selects_only_its_data_root_pwa_and_manifest_and_blocks_write_routes(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        a, ah = register(client)
        b, bh = register(client)
        assert a['viewer_url'].startswith('https://user-')
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
        assert len(snapshot['labels']) == 2 and 'owner_hash' not in str(snapshot)


def test_migrating_long_dns_replaces_the_only_address_but_keeps_history_and_owner(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        profile, headers = register(client, claim=True)
        client.post('/v1/glucose/readings', headers={'Authorization': 'Bearer ' + TEST_TOKEN},
                    json=reading_batch(int(time.time() * 1000))).raise_for_status()
        old_label = 'gluco-' + 'a' * 26
        old = 'https://' + old_label + '.example.com/'
        with app.state.connections.registry.connect() as registry:
            registry.execute('UPDATE installations SET dns_label = ? WHERE id = ?',
                             (old_label, profile['installation_id']))
            registry.execute('PRAGMA user_version = 0')
        described = client.get(profile['api_path'] + '/connection', headers=headers).json()
        short = described['viewer_url']
        assert short.startswith('https://user-') and len(short) < len(old)
        assert len(urlparse(short).hostname.split('.')[0]) == 17
        assert described['viewer_ready'] is False
        assert described['installation_id'] == profile['installation_id']
        assert described['api_path'] == profile['api_path']
        assert described['viewer_path'] == profile['viewer_path']
        assert client.get(old + 'v1/viewer/snapshot').status_code == 401
        assert client.get(short + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(short).status_code == 200
        assert client.get(short + 'viewer/personal.webmanifest').json()['start_url'] == '/'
        assert described == client.get(profile['api_path'] + '/connection', headers=headers).json()
        routing = json.loads((app.state.connections.directory / 'public-routing/viewer-domains.json').read_text())
        assert routing['labels'] == [urlparse(short).hostname.split('.')[0]]
        publish_status(app.state.connections.directory / 'dns-status.json', [urlparse(short).hostname])
        described = client.get(profile['api_path'] + '/connection', headers=headers).json()
        assert described['viewer_ready'] is True
        other, _ = register(client)
        assert client.get(other['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is None
        operation = {'operation_id': str(uuid4()), 'label': 'vlad'}
        rotated = client.post(profile['api_path'] + '/connection', headers=headers, json=operation).json()
        assert rotated['viewer_url'] == 'https://vlad.example.com/'
        assert rotated['api_path'] == profile['api_path'] and rotated['installation_id'] == profile['installation_id']
        assert rotated == client.post(profile['api_path'] + '/connection', headers=headers, json=operation).json()
        for url in (short, old):
            assert client.get(url + 'v1/viewer/snapshot').status_code == 401
        assert client.get(rotated['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(profile['api_path'] + '/v1/intakes', headers=headers).status_code == 200


def test_compact_collisions_retry_and_never_replace_an_existing_profile(tmp_path, monkeypatch):
    registry = ConnectionRegistry(tmp_path / 'registry.sqlite3')
    first = registry.register(str(uuid4()), secrets.token_urlsafe(32))
    candidate = domain_label()
    labels = iter([first['dns_label'], first['dns_label'], candidate])
    monkeypatch.setattr('app.connections.domain_label', lambda: next(labels))
    identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
    second = registry.register(identifier, secret)
    assert second['dns_label'] == candidate
    assert registry.get_domain(first['dns_label'])['id'] == first['id']
    assert registry.register(identifier, secret)['dns_label'] == candidate
    monkeypatch.setattr('app.connections.domain_label', lambda: candidate)
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
        label = row['dns_label']
        assert DNS_LABEL.fullmatch(label) and len(label) == 17
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
        assert changed['viewer_url'] == 'https://my-sugar.example.com/'
        assert changed == client.post(a['api_path'] + '/connection', headers=ah, json=operation).json()
        assert client.get(a['viewer_url'] + 'v1/viewer/snapshot').status_code == 401
        assert client.get(changed['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(viewer(changed)).json()['current_glucose'] is not None
        status_path.write_text(json.dumps({'checked_at': time.time() - 200, 'ready_hosts': [host]}))
        assert not domain_ready(status_path, host)


def test_custom_name_replaces_random_label_and_conflicts_preserve_current_links(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        a, ah = register(client)
        b, bh = register(client)
        def rename(profile, headers, name):
            return client.post(profile['api_path'] + '/connection', headers=headers,
                               json={'operation_id': str(uuid4()), 'label': name})
        client.post(a['api_path'] + '/v1/glucose/readings', headers=ah,
                    json=reading_batch(int(time.time() * 1000))).raise_for_status()
        named = rename(a, ah, ' Vlad ').json()
        assert named['viewer_url'] == 'https://vlad.example.com/'
        assert client.get(a['viewer_url']).status_code == 401
        # Another operation with the same name is a no-op, not another key rotation.
        assert rename(a, ah, 'vlad').json() == named
        taken = rename(b, bh, 'vlad')
        assert taken.status_code == 409
        assert client.get(b['api_path'] + '/connection', headers=bh).json() == b
        assert client.get(named['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(b['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is None
        renamed = rename(a, ah, 'my-sugar').json()
        assert renamed['viewer_url'] == 'https://my-sugar.example.com/'
        assert client.get(named['viewer_url']).status_code == 401
        # Keep old browser caches/private links from ever attaching to a new person.
        assert rename(b, bh, 'vlad').status_code == 409
        assert rename(a, ah, 'vlad').json()['viewer_url'] == named['viewer_url']
        random = rename(a, ah, '').json()
        assert random['viewer_url'].startswith('https://user-')
        assert client.get(named['viewer_url']).status_code == 401
        assert client.get(random['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert random['api_path'] == a['api_path']
        assert client.get(a['api_path'] + '/v1/intakes', headers=ah).status_code == 200
        routes = json.loads((app.state.connections.directory / 'public-routing/viewer-domains.json').read_text())
        assert set(routes['labels']) == {urlparse(random['viewer_url']).hostname.split('.')[0],
                                        urlparse(b['viewer_url']).hostname.split('.')[0]}
        # Restart must not migrate a custom label again.
        last = rename(a, ah, 'x' * 63).json()
        registry = ConnectionRegistry(app.state.connections.registry.path)
        assert registry.get(a['installation_id'])['dns_label'] == 'x' * 63
        assert last['viewer_url'] == 'https://' + 'x' * 63 + '.example.com/'


def test_invalid_or_infrastructure_names_never_publish_routes(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        a, ah = register(client)
        for name in ['-bad', 'bad-', 'a b', 'сахар', 'a.b', 'a/b', '*', 'x' * 64,
                     'user-test', 'api', 'internal', 'harbor', 'argocd', 'juggluco-general1',
                     'job-crm-dev-general1', 'general2', 'online-shop-general2', None, 12]:
            response = client.post(a['api_path'] + '/connection', headers=ah,
                                   json={'operation_id': str(uuid4()), 'label': name})
            assert response.status_code == 422, name
        assert client.get(a['api_path'] + '/connection', headers=ah).json() == a


def test_two_owners_racing_for_one_name_have_exactly_one_winner(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    from fastapi import HTTPException
    registry = ConnectionRegistry(tmp_path / 'registry.sqlite3')
    owners = [(str(uuid4()), secrets.token_urlsafe(32)) for _ in range(2)]
    originals = [registry.register(identifier, secret) for identifier, secret in owners]
    def claim(owner):
        try:
            return registry.rotate(*owner, str(uuid4()), 'same-name')
        except HTTPException as error:
            assert error.status_code == 409
            return None
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(claim, owners))
    assert sum(row is not None for row in results) == 1
    for index, row in enumerate(results):
        if row is None:
            assert registry.get(owners[index][0])['dns_label'] == originals[index]['dns_label']
        else:
            assert registry.get_domain('same-name')['id'] == owners[index][0]


def test_live_host_stream_is_revoked_even_before_dns_removal(tmp_path):
    async def exercise():
        app = create_app(settings(tmp_path))
        manager = app.state.connections
        identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
        row = manager.registry.register(identifier, secret)
        scope = _stream_scope([])
        scope['headers'] = [(b'host', (row['dns_label'] + '.example.com').encode())]
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
    labels = [domain_label() for _ in range(32)] + ['my-sugar']
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
    for malicious in [['*.example.com'], ['../escape'], ['UPPERCASE'], ['api'], ['internal'], ['keycloak-general1'], ['name-'], 'not-a-list']:
        with pytest.raises(ValueError): desired_routes(malicious, 'example.com', 'ns', 'ns', 'public', 'https')


def test_readiness_probe_requires_matching_host_and_valid_success():
    host = domain_label() + '.example.com'
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
