/** A viewer capability is explicit in the URL; never infer it from a prior tab. */
export function profileContext(search = window.location.search, hostname = window.location.hostname) {
  // DNS names are separate browser origins. Even malformed personal names must
  // never use a legacy/another-profile cache or let ?profile override its host.
  if (/^(?:gluco-|g-)/.test(hostname.toLowerCase())) {
    return { key: `host:${hostname.toLowerCase()}`, prefix: '/v1/viewer', handle: null }
  }
  const query = new URLSearchParams(search)
  const values = query.getAll('profile')
  if (!values.length) return { key: 'legacy', prefix: '/v1/viewer', handle: null }
  const handle = values[0]
  if (values.length !== 1 || !/^(?:[a-z0-9][a-z0-9-]{0,23}-)?[A-Za-z0-9_-]{22}$/.test(handle)) {
    // Malformed personal links MUST NOT fall back to the old single-user viewer.
    return { key: 'invalid', prefix: '/v1/shares/invalid', handle: null }
  }
  return { key: `profile:${handle}`, prefix: `/v1/shares/${handle}`, handle }
}

export function viewerEndpoint(endpoint: string) {
  return `${profileContext().prefix}/${endpoint}`
}

export function profileStorageKey(key: string) {
  const profile = profileContext()
  return profile.key === 'legacy' ? key : `${key}:${profile.key}`
}

export function configurePersonalManifest() {
  const { handle, key } = profileContext()
  if (!handle && !key.startsWith('host:')) return
  let manifest = document.querySelector<HTMLLinkElement>('link[rel="manifest"]')
  if (!manifest) {
    manifest = document.createElement('link')
    manifest.rel = 'manifest'
    document.head.appendChild(manifest)
  }
  manifest.href = handle ? `/viewer/personal.webmanifest?profile=${encodeURIComponent(handle)}`
    : '/viewer/personal.webmanifest'
}
