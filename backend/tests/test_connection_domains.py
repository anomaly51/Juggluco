import asyncio
import copy
import json
import re
import secrets
import sqlite3
import time
from types import SimpleNamespace
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


@pytest.mark.parametrize('version', [0, 1])
@pytest.mark.parametrize('old_label', [None, 'gluco-my-sugar-' + 'a' * 26, 'user-' + 'a' * 12, 'vlad-sugar'])
def test_dns_labels_are_short_and_migration_revokes_old_dns_without_changing_owners(tmp_path, old_label, version):
    handle = 'my-sugar-' + secrets.token_urlsafe(16)
    label = domain_label()
    assert DNS_LABEL.fullmatch(label)
    assert re.fullmatch(r'user-[a-z2-7]{5}', label)
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
        old.execute(f'PRAGMA user_version = {version}')
    registry = ConnectionRegistry(registry_path)
    migrated = registry.authenticate(identifier, secret)
    assert migrated['handle'] == handle and migrated['legacy'] == 1
    compact = migrated['dns_label']
    assert compact.startswith('user-') and len(compact.rsplit('-', 1)[1]) == 5
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
        assert len(urlparse(short).hostname.split('.')[0]) == 10
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
        assert re.fullmatch(r'https://vlad-[a-z2-7]{5}\.example\.com/', rotated['viewer_url'])
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
    monkeypatch.setattr('app.connections.domain_label', lambda name='user': next(labels))
    identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
    second = registry.register(identifier, secret)
    assert second['dns_label'] == candidate
    assert registry.get_domain(first['dns_label'])['id'] == first['id']
    assert registry.register(identifier, secret)['dns_label'] == candidate
    monkeypatch.setattr('app.connections.domain_label', lambda name='user': candidate)
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
        assert DNS_LABEL.fullmatch(label) and len(label) == 10
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
        assert re.fullmatch(r'https://my-sugar-[a-z2-7]{5}\.example\.com/', changed['viewer_url'])
        assert changed == client.post(a['api_path'] + '/connection', headers=ah, json=operation).json()
        assert client.get(a['viewer_url'] + 'v1/viewer/snapshot').status_code == 401
        assert client.get(changed['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(viewer(changed)).json()['current_glucose'] is not None
        status_path.write_text(json.dumps({'checked_at': time.time() - 200, 'ready_hosts': [host]}))
        assert not domain_ready(status_path, host)


def test_custom_names_have_short_suffixes_and_shared_names_never_share_history(tmp_path):
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
        assert re.fullmatch(r'https://vlad-[a-z2-7]{5}\.example\.com/', named['viewer_url'])
        assert client.get(a['viewer_url']).status_code == 401
        # Another operation with the same name is a no-op, not another key rotation.
        assert rename(a, ah, 'vlad').json() == named
        other_named = rename(b, bh, 'vlad').json()
        assert re.fullmatch(r'https://vlad-[a-z2-7]{5}\.example\.com/', other_named['viewer_url'])
        assert other_named['viewer_url'] != named['viewer_url']
        assert client.get(named['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert client.get(other_named['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is None
        renamed = rename(a, ah, 'my-sugar').json()
        assert re.fullmatch(r'https://my-sugar-[a-z2-7]{5}\.example\.com/', renamed['viewer_url'])
        assert client.get(named['viewer_url']).status_code == 401
        # Keep old browser caches/private links from ever attaching to a new person.
        assert rename(b, bh, 'vlad').json() == other_named
        assert rename(a, ah, 'vlad').json()['viewer_url'] != named['viewer_url']
        random = rename(a, ah, '').json()
        assert random['viewer_url'].startswith('https://user-')
        assert client.get(named['viewer_url']).status_code == 401
        assert client.get(random['viewer_url'] + 'v1/viewer/snapshot').json()['current_glucose'] is not None
        assert random['api_path'] == a['api_path']
        assert client.get(a['api_path'] + '/v1/intakes', headers=ah).status_code == 200
        routes = json.loads((app.state.connections.directory / 'public-routing/viewer-domains.json').read_text())
        assert set(routes['labels']) == {urlparse(random['viewer_url']).hostname.split('.')[0],
                                        urlparse(other_named['viewer_url']).hostname.split('.')[0]}
        # Restart must not migrate a custom label again.
        last = rename(a, ah, 'x' * 57).json()
        registry = ConnectionRegistry(app.state.connections.registry.path)
        assert len(registry.get(a['installation_id'])['dns_label']) == 63
        assert registry.get(a['installation_id'])['dns_label'] == urlparse(last['viewer_url']).hostname.split('.')[0]
        assert last['viewer_url'].startswith('https://' + 'x' * 57 + '-')


def test_invalid_or_infrastructure_names_never_publish_routes(tmp_path):
    app = create_app(settings(tmp_path))
    with TestClient(app, base_url='https://testserver') as client:
        a, ah = register(client)
        for name in ['-bad', 'bad-', 'a b', 'сахар', 'a.b', 'a/b', '*', 'x' * 58,
                     'user-test', 'api', 'internal', 'harbor', 'argocd', 'juggluco-general1',
                     'job-crm-dev-general1', 'general2', 'online-shop-general2', None, 12]:
            response = client.post(a['api_path'] + '/connection', headers=ah,
                                   json={'operation_id': str(uuid4()), 'label': name})
            assert response.status_code == 422, name
        assert client.get(a['api_path'] + '/connection', headers=ah).json() == a


def test_two_owners_racing_for_one_name_receive_different_suffixes(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    registry = ConnectionRegistry(tmp_path / 'registry.sqlite3')
    owners = [(str(uuid4()), secrets.token_urlsafe(32)) for _ in range(2)]
    for identifier, secret in owners:
        registry.register(identifier, secret)
    def claim(owner):
        return registry.rotate(*owner, str(uuid4()), 'same-name')
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(claim, owners))
    assert results[0]['dns_label'] != results[1]['dns_label']
    for index, row in enumerate(results):
        assert re.fullmatch(r'same-name-[a-z2-7]{5}', row['dns_label'])
        assert registry.get_domain(row['dns_label'])['id'] == owners[index][0]


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
            assert public_ready(client, host, SimpleNamespace(ready=lambda _: True)) is expected


def test_unpublished_name_never_reaches_recursive_https_lookup():
    def forbidden(request):
        pytest.fail('HTTPS must wait for authoritative publication')
    with httpx.Client(transport=httpx.MockTransport(forbidden)) as client:
        assert not public_ready(client, 'user-abcde.example.com', SimpleNamespace(ready=lambda _: False))


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
