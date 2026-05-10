# Pano Core — Plugin License Subsystem

Transport and UX for premium Pano plugins: the **open-source host** forwards license requests to
panomc.com, parses the returned JWT **without RS256 verification**, caches claims for the panel, and
tracks failures. **Premium (closed-source) plugins** are the cryptographic enforcement boundary: they
call [SignedLicense.verifySignature] with an **embedded public key** at build time and refuse to run
if verification fails.

When a premium plugin fails its license check it does not start, but Pano itself continues running
normally. The panel surfaces the issue (badges, license card, dashboard banner) without blocking the
whole platform.

**No public key is embedded in Pano core** and **no extra HTTP round-trip** runs on startup; the JWT
is obtained only when a plugin requests a license (or the operator hits refresh in the panel).

After plugins start, [`LicenseManager.init`](LicenseManager.kt) schedules a periodic background sweep
that proactively renews cached JWTs around their half-life (license tokens are short-lived — the
default issuer TTL is 1h) and force-disables any plugin whose license has lapsed and cannot be
renewed (purchase refunded, account disconnected, version mismatch, …) so it stops serving routes
and event handlers instead of silently running with an unverifiable token.

## Files

- [`LicenseManager.kt`](LicenseManager.kt) — Spring `@Component`. Receives `requireLicense` from
  plugins, fetches a JWT via [`PanoApiManager.issueLicense`](../PanoApiManager.kt), parses claims
  (unsigned on the host), cross-checks basic fields vs the request, caches for the panel, records
  failures, and runs the post-startup periodic renewal sweep.
- [`SignedLicense.kt`](SignedLicense.kt), [`LicenseClaims.kt`](LicenseClaims.kt) — wrappers around
  the RS256 JWT; plugins verify via [SignedLicense.verifySignature].
- [`LicenseRequiredException.kt`](LicenseRequiredException.kt), [`LicenseDeniedReason.kt`](LicenseDeniedReason.kt),
  [`PluginLicenseFailure.kt`](PluginLicenseFailure.kt), [`LicensePanelView.kt`](LicensePanelView.kt) —
  failures and panel-facing status.

## End-to-end flow

```
[Pano start]
  ├── PluginManager.startPlugins()
  │     └── for each plugin: plugin.onStart()
  │           └── premium plugin calls PluginLicenseClient.requireValidLicense()
  │                 ├── LicenseManager.requireLicense(plugin, resourceId, version)
  │                 │     ├── jarSha256 from PanoPluginWrapper.hash
  │                 │     ├── PanoApiManager.issueLicense(...) -> panomc.com (TLS)
  │                 │     ├── JWT.decode (host does NOT verify RS256)
  │                 │     ├── cross-check claims (aud, ver, hash, exp, sub vs config)
  │                 │     └── cache SignedLicense for panel / return to plugin
  │                 └── plugin verifies RS256 with embedded public key (authoritative)
  └── PluginManager.captureLicenseFailureIfAny()
        └── LicenseManager.recordFailure(...) when plugin throws

[panel UI]
  - GET /api/panel/plugins/:id/license          -> claims + status (from cache / failures)
  - POST /api/panel/plugins/:id/license/refresh -> host re-fetch + parse (plugin verifies on restart)
  - GET /api/panel/dashboard                    -> licenseFailedPluginCount

[periodic renewal sweep] (LicenseManager.init, scheduled once after plugins start)
  - every minute, for each STARTED plugin that has used the DRM flow this session:
      - if cached token has passed its half-life: renew via PanoApiManager.issueLicense
      - on success: cache fresh JWT, clear stale failure (if any)
      - on failure WHILE token still valid: log warn, retry next tick
      - on failure AND token is past expiry: record failure, stop+disable plugin (and dependents)
```

## Key rotation runbook (v1)

v1 uses a single signing key on the website. Rotation:

1. **Pre-rotate**: announce a maintenance window.
2. **Generate new keypair** on the website (`license-keys/` reset + restart `pano-platform-management`).
3. **Rebuild each premium plugin** with `-PlicenseServer=prod` (or `-PpanoLicensePublicKey=…`) so the
   new public key is embedded in `PluginBuildConstants`.
4. **Ship plugin updates**. Pano core does **not** need a rebuild solely for the new public key.
5. Operators update premium plugins; tokens TTL as before.

A future v2 may support dual-key transition for zero-downtime rotation.
