from types import SimpleNamespace

import dns.exception
import dns.flags
import dns.message
import dns.name
import dns.rcode
import dns.rrset
import pytest

from app.connection_dns_publication import DnsPublication


@pytest.mark.parametrize('mode,expected', [
    ('ready', True), ('missing', False), ('empty', False), ('recursive', False),
    ('wrong_name', False), ('timeout', False), ('second_pending', False), ('cname', True),
])
def test_requires_publication_on_every_authority_without_recursion(mode, expected):
    calls = []
    def query(request, server, timeout):
        assert not request.flags & dns.flags.RD
        assert timeout == 3
        calls.append(server)
        if mode == 'timeout':
            raise dns.exception.Timeout
        response = dns.message.make_response(request)
        response.flags |= dns.flags.AA
        if mode == 'missing' or mode == 'second_pending' and server == '192.0.2.2':
            response.set_rcode(dns.rcode.NXDOMAIN)
        elif mode != 'empty':
            name = 'other.example.com' if mode == 'wrong_name' else request.question[0].name
            record_type, value = ('CNAME', 'edge.example.com.') if mode == 'cname' else ('A', '192.0.2.3')
            response.answer.append(dns.rrset.from_text(name, 300, 'IN', record_type, value))
        if mode == 'recursive':
            response.flags &= ~dns.flags.AA
        return response, False
    probe = DnsPublication('example.com', query=query)
    probe.authorities = lambda: ('192.0.2.1', '192.0.2.2')
    assert probe.ready('user-abcde.example.com') is expected
    assert calls
    if expected:
        assert len(calls) == 2


def test_nameservers_cached_but_personal_names_never_resolved_recursively(monkeypatch):
    queries = []
    now = [100]
    def resolve(name, kind, lifetime):
        queries.append((str(name), kind))
        assert lifetime <= 5
        if kind == 'NS':
            return [SimpleNamespace(target=dns.name.from_text('ns.example.net'))]
        return [SimpleNamespace(address='192.0.2.1')]
    monkeypatch.setattr(dns.resolver, 'zone_for_name', lambda name, **kwargs: name)
    probe = DnsPublication('example.com', resolver=SimpleNamespace(resolve=resolve), clock=lambda: now[0])
    assert probe.authorities() == ('192.0.2.1',)
    assert probe.authorities() == ('192.0.2.1',)
    assert queries == [('example.com.', 'NS'), ('ns.example.net.', 'A')]
    now[0] += 301
    probe.authorities()
    assert len(queries) == 4


@pytest.mark.parametrize('hostname', ['example.com', 'user-abcde.evil.com', 'nested.user.example.com',
                                     '*.example.com', 'api.example.com'])
def test_invalid_names_never_send_dns_queries(hostname):
    probe = DnsPublication('example.com')
    probe.authorities = lambda: pytest.fail('invalid hostname reached network')
    assert not probe.ready(hostname)
