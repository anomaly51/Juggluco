"""Check publication without filling recursive resolvers with premature NXDOMAIN.

Only the existing zone/NS names use recursion. New personal names go directly
to each authoritative nameserver, with recursion disabled. This is a gate for
the subsequent HTTPS check, never a replacement for TLS or phone verification.
"""
import threading
import time

import dns.exception
import dns.flags
import dns.message
import dns.name
import dns.query
import dns.rcode
import dns.rdatatype
import dns.resolver

from .connection_domains import DOMAIN, allowed_viewer_label


class DnsPublication:
    def __init__(self, domain, resolver=None, query=None, clock=time.monotonic):
        if not DOMAIN.fullmatch(domain):
            raise ValueError('invalid viewer domain')
        self.domain = domain
        self.resolver = resolver or dns.resolver.Resolver()
        self.query = query or dns.query.udp_with_fallback
        self.clock = clock
        self.lock = threading.Lock()
        self.servers = ()
        self.expires = 0

    def authorities(self):
        with self.lock:
            if self.servers and self.clock() < self.expires:
                return self.servers
            # The configured domain already exists; never discover a zone by
            # recursively looking up a not-yet-published personal hostname.
            zone = dns.resolver.zone_for_name(dns.name.from_text(self.domain),
                                              resolver=self.resolver, lifetime=5)
            names = list(self.resolver.resolve(zone, 'NS', lifetime=5))
            if not 1 <= len(names) <= 8:
                raise ValueError('invalid authoritative server count')
            servers = []
            for item in names:
                addresses = self.resolver.resolve(item.target, 'A', lifetime=3)
                servers.append(addresses[0].address)
            self.servers = tuple(servers)
            self.expires = self.clock() + 300
            return self.servers

    def ready(self, hostname):
        suffix = '.' + self.domain
        if not hostname.endswith(suffix) or not allowed_viewer_label(hostname[:-len(suffix)]):
            return False
        try:
            request = dns.message.make_query(hostname, dns.rdatatype.A)
            request.flags &= ~dns.flags.RD
            servers = self.authorities()
            for server in servers:
                reply, _ = self.query(request, server, timeout=3)
                if not reply.flags & dns.flags.AA or reply.rcode() != dns.rcode.NOERROR:
                    return False
                if not any(rrset.name == request.question[0].name and len(rrset)
                           and rrset.rdtype in (dns.rdatatype.A, dns.rdatatype.CNAME)
                           for rrset in reply.answer):
                    return False
            return bool(servers)
        except (dns.exception.DNSException, OSError, ValueError):
            return False
