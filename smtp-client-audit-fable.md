# smtp-client Audit (Fable)

> **Remediation status (2026-06-09):** all findings fixed and covered by tests in the same
> change-set — F1–F22 and test gaps T1–T17 (≈100 new tests; suite now 230 unit tests + 2
> Docker-gated integration tests, `:smtp-client:check` green).
> Deliberate deviations from the recommendations:
> - **F4** — MX routing still routes on the *sender* domain (swaks `--copy-routing` parity, now
>   loudly documented on `TransportConfig`); multi-MX fallback, null-MX refusal, and
>   equal-preference shuffling are implemented.
> - **F9** — AUTO selection now filters by credential kind (`AuthCredentials.Kind`); automatic
>   *retry with the next mechanism after a 535* was intentionally not added (one deterministic
>   attempt per send keeps the transcript honest).
> - **F12** — reply line/count caps added; a per-transaction wall-clock deadline (beyond
>   `SO_TIMEOUT` per read) remains future work.
> - **F20** — `optionalStrict` was *implemented* (swaks `--auth-optional-strict` semantics)
>   rather than removed, because the plugin's profile UI already plumbs it.
> - **F11** — encrypted PKCS#8 keys remain unsupported but now fail with an actionable message.
> - **T5** — CRAM-MD5 is pinned to the RFC 2195 vector; DIGEST-MD5 (JDK provider) remains
>   untested.

**Date:** 2026-06-09
**Scope:** entire `smtp-client` subproject — `src/main` (35 files, ~4,070 LOC), `src/test` (30 files), `src/integrationTest` (2 files), `build.gradle`.
**Reviewer stance:** senior Java / SMTP-protocol review aimed at publishing this module as a standalone library.
**Method:** full read of every main-source file; protocol behavior checked against RFC 5321/2920/3461/1870/6531/3030 (BDAT), RFC 4954/4616/5802/7677/7628 (AUTH/SASL), the PRDR draft (Exim), HAProxy PROXY spec, and Postfix XCLIENT; test inventory mapped method-by-method against the feature surface.

Previously accepted decisions are **not** re-raised: insecure-by-default `TlsConfig.none()` / egress defaults were reviewed earlier (AUDIT F6) and explicitly kept by the owner.

---

## Verdict in one paragraph

The wire core is in very good shape: dot-stuffing/CRLF normalization is correct across chunk boundaries, command-injection is defended at two layers (envelope validation + `writeCommand` guard), the stop/drop-after phase matrix is fully implemented and fully tested, SCRAM uses a constant-time compare and clamps hostile iteration counts, and the trust/hostname-verification controls are properly decoupled. What blocks a confident 1.0 as a *library* are: a TLS-failure misclassification (F1), bearer tokens leaking over cleartext sockets (F2), a PRDR implementation that only interoperates with its own test fake (F3), MX routing that can't actually be relied on for delivery (F4), and unchecked exceptions escaping the `SmtpException` contract (F5). Test coverage is strong on happy paths and the stop/drop matrix, but thin on error paths — exactly where the bugs above live.

---

## Findings — code

Ranked by severity. File references are `path:line` in `smtp-client/src/main/java/com/github/ttereshchenko/mailkit/smtp/`.

### High

**F1. TLS handshake failures surface as `IO_ERROR`, not `TLS_FAILED`**
`SmtpClient.upgradeToTls` (`SmtpClient.java:420-452`) catches only `GeneralSecurityException`, but `SSLSocket.startHandshake()` (line 432) reports failures — untrusted chain, hostname mismatch, protocol/cipher mismatch — as `SSLHandshakeException`, which is an `IOException`. It therefore bypasses the catch and is mapped to `Kind.IO_ERROR` by the outer handler (`SmtpClient.java:81-89`). Any caller routing on `Kind.TLS_FAILED` (UI messaging, retry policy, the "allow self-signed?" prompt) gets the wrong category for the single most common TLS failure. Fix: also catch `SSLException`/`SSLHandshakeException` inside `upgradeToTls` and wrap as `TLS_FAILED`. No test currently exercises a failing handshake (see T1).

**F2. Bearer tokens (XOAUTH2 / OAUTHBEARER) are sent over cleartext sockets without the plaintext-auth gate**
`AuthMechanism.isPlaintextOnTheWire()` (`auth/AuthMechanism.java:31-34`) returns true only for PLAIN / LOGIN / CRAM-MD5. The pre-flight refusal in `performAuth` (`SmtpClient.java:753-758`) therefore lets XOAUTH2 and OAUTHBEARER proceed on a non-TLS socket without `allowPlaintextAuth`: the base64 blob contains the live bearer token, a reusable credential equivalent to a password. Add both to the plaintext-on-the-wire set. (Side note: the Javadoc claims CRAM-MD5 "exposes credentials in cleartext" — it doesn't; it's gated for being *weak*, which is fine, but the comment is wrong. DIGEST-MD5 is equally weak and not gated — worth a deliberate decision.)

**F3. PRDR response handling doesn't match what PRDR servers actually send**
`readPrdrPerRecipientResponses` (`SmtpClient.java:707-732`) assumes the server sends exactly one reply per accepted recipient followed by one overall reply. Per the PRDR draft and the only mainstream implementation (Exim), the server first sends a **`353`** intermediate reply, *then* the per-recipient replies, *then* the overall reply — and it **may instead send one single normal reply** when the verdict is uniform. Against Exim the current code consumes the 353 as recipient #1's verdict (marking it rejected), shifts every subsequent disposition by one, never reads the overall reply, and then desynchronizes on QUIT. The same applies to the PRDR-after-BDAT path (`SmtpClient.java:305-307`), where `performBdat` additionally expects a 2xx ack that a PRDR-in-force server won't send. Fix: read one response; if 353, loop per-recipient then read the final; if it's a normal completion/failure code, treat it as the uniform verdict. The unit tests only script the home-grown framing (see T3).

### Medium

**F4. MX routing is not usable for real delivery**
`resolveDestinationHost` (`SmtpClient.java:540-575`) + `transport/MxResolver.java`:
- Routes on the **MAIL FROM** domain. Documented (swaks `--copy-routing` parity), but recipients' domains are never consulted — for a library API named "MX routing" this is a loaded footgun; at minimum the Javadoc on `TransportConfig.withMxRouting` should shout it.
- Only `mxHosts.get(0)` is ever tried. RFC 5321 §5.1 requires falling back to lower-priority MX hosts when connection fails; here a single dead primary MX fails the send.
- RFC 7505 null MX (`0 .`) is not recognized: the trailing-dot strip (`MxResolver.java:71-73`) turns it into an empty hostname and the client tries to connect to `""` instead of failing fast with "domain accepts no mail".
- Equal-preference records are not shuffled (RFC 5321 §5.1 SHOULD).

**F5. Auth-layer `RuntimeException`s escape `send()` unwrapped**
Every hand-rolled auth client signals protocol problems with `IllegalStateException`: SCRAM nonce-echo failure, malformed server-first, **server-signature verification failure** (`auth/ScramAuthClient.java:101-148`), LOGIN round overflow, PLAIN/EXTERNAL `respond()`, and `SaslAuthClient` wrapping `SaslException` (`auth/SaslAuthClient.java:56-67`). `performAuth` only wraps exceptions from `AuthClients.create` (`SmtpClient.java:760-769`); a failure mid-conversation propagates out of `SmtpClient.send` as an unchecked exception — breaking the documented `SmtpException` contract, skipping `Kind.AUTH_FAILED`, and losing the transcript attachment. Wrap the challenge/response loop in a catch that converts to `AUTH_FAILED`.

**F6. Empty SASL initial response must be `=` (RFC 4954 §4)**
`performAuth` (`SmtpClient.java:770-775`): when `client.initial()` returns an empty array (EXTERNAL with no authzid — `auth/ExternalAuthClient.java:25-28`), base64 of `[]` is `""` and the wire line becomes `AUTH EXTERNAL ` (trailing space). The RFC requires `AUTH EXTERNAL =` to signal "empty initial response present". Strict servers reject or stall on the current form.

**F7. SCRAM cannot complete when the server puts server-final in the 235 reply**
RFC 4954 allows the server to carry SASL "additional data" (the SCRAM server-final `v=...`) base64-encoded in the **success** `235` reply instead of a final empty `334` round; several servers do. `performAuth` exits the 334 loop, sees `isComplete() == false` and throws (`SmtpClient.java:790-795`). Fail-closed, which is safe, but it's a real interop failure, and the server signature goes unverified rather than verified-from-235. Handle 235-with-payload by feeding the decoded payload to `respond()` before the completeness check.

**F8. ESMTP parameter injection / missing xtext encoding on DSN and XCLIENT values**
`SmtpEnvelope.requireNoLineBreaks` blocks CR/LF/NUL only. `DsnEnvelopeFormatter` (`esmtp/DsnEnvelopeFormatter.java:16-41`) concatenates `mailFrom`, `envid`, `orcpt` raw:
- A `mailFrom` like `a@b> SIZE=0 AUTH=<>` injects extra MAIL parameters (no CRLF needed); same for recipient addresses on RCPT.
- RFC 3461 §4.4 requires **xtext** encoding for ENVID and the ORCPT address; spaces or `+`/`=` in those values currently produce malformed or misparsed commands.
- Same class of issue in `xclient/XclientCommandBuilder.java:32-41` — Postfix expects xtext-encoded attribute values; an embedded space splits the attribute.
Inputs come from the local caller, so this is hardening rather than remote attack surface — but for a published library, validate `<`/`>`/space/control characters in addresses and xtext-encode ENVID/ORCPT/XCLIENT values.

**F9. AUTO mechanism selection ignores credential shape and never falls back**
`AuthMechanismSelector.AUTO_ORDER` (`auth/AuthMechanismSelector.java:17-26`) will pick XOAUTH2/OAUTHBEARER for a caller holding a *password* (e.g. server advertises `AUTH PLAIN XOAUTH2` and no SCRAM — XOAUTH2 outranks PLAIN), sending the password as a bearer token; the inverse holds for token credentials and SCRAM/PLAIN. There is also no retry with the next-ranked mechanism after a 535. Either tag `AuthCredentials` with a kind (password vs token vs external) and filter AUTO by it, or document loudly that AUTO is only safe with password credentials.

**F10. `Protocol.LMTP` is accepted but LMTP is not implemented**
`SmtpConfig.Protocol` offers LMTP, but `issueEhlo` (`SmtpClient.java:824`) sends `EHLO` (not `LHLO`), and the DATA-terminator handling reads a single reply instead of RFC 2033's one-reply-per-accepted-recipient. Selecting LMTP silently behaves as ESMTP and desynchronizes against a real LMTP server. Implement, or reject the value with `UnsupportedOperationException` until implemented.

**F11. mTLS private-key loading is unencrypted-PKCS#8-RSA only**
`TlsContextFactory.loadPrivateKey` (`tls/TlsContextFactory.java:169-182`) requires `-----BEGIN PRIVATE KEY-----` and hardcodes `KeyFactory.getInstance("RSA")`. EC client keys (the modern default), PKCS#1 `BEGIN RSA PRIVATE KEY`, and encrypted keys all fail — EC with a misleading "invalid key spec" error. At minimum try RSA→EC→Ed25519 key factories and document the PKCS#8-unencrypted constraint.

**F12. `readResponse` is unbounded**
`SmtpClient.java:961-1003` accumulates multi-line replies with no cap on line count or line length; a hostile/buggy server can stream `250-...` forever and exhaust memory (the transcript also retains every line twice over). Cap reply lines (the practical limit is a few hundred for EHLO) and line length, failing with `PROTOCOL_VIOLATION`. Related: the only time bound is `SO_TIMEOUT` per read — there is no overall transaction deadline.

### Low

**F13. EHLO parser interop gaps** — `esmtp/EhloResponseParser.java:33-53`: legacy `AUTH=PLAIN LOGIN` advertisements (still emitted by old servers, special-cased by every mainstream client) are parsed as keyword `AUTH=PLAIN`, so auth appears unadvertised; duplicate keyword lines (e.g. two `AUTH` lines) overwrite instead of merging.

**F14. `STARTTLS_OPTIONAL` downgrades on *rejection*, not just absence** — `issueStartTlsCommand` (`SmtpClient.java:408-411`) continues in cleartext when an advertised STARTTLS is answered 4xx/5xx; the mode's Javadoc only promises fall-through when "not advertised". Behavior may be intended (STRICT exists for fatal), but document it and drop an INFO transcript line on the downgrade.

**F15. No EHLO→HELO fallback** — RFC 5321 §3.2: on a 5xx to EHLO a client SHOULD retry HELO for ancient servers; current code throws `PROTOCOL_VIOLATION` (`SmtpClient.java:826-831`). `Protocol.SMTP` is a manual workaround; an automatic fallback (or at least a clearer error) would match user expectations of a swaks-alike.

**F16. `defaultEhloHost` can block and can emit a bare IP** — `SmtpConfig.java:208-215` calls `getCanonicalHostName()` (reverse-DNS, can stall seconds on misconfigured resolvers, on every `defaults()` call) and may return an IP literal, which RFC 5321 §4.1.3 requires to be bracketed (`[192.0.2.1]`) in EHLO.

**F17. Sub-millisecond timeout becomes infinite** — `TcpConnector.java:44`: `timeout.toMillis()` truncates `Duration.ofNanos/ofMillis(<1ms)` to `0`, which `Socket` interprets as *infinite* for both connect and read. Clamp to a 1 ms minimum.

**F18. BDAT buffers the entire message** — `readAndNormalize` (`SmtpClient.java:683-705`) is documented as a deliberate single-chunk simplification, but for a library it deserves a size guard or streaming multi-chunk BDAT; also the `SIZE=` MAIL parameter uses `source.size()` while the transmitted byte count is the post-normalization length (cosmetic mismatch).

**F19. Production code carries a test escape hatch and an incomplete private-range guard** — `TcpConnector.pickCandidates` (`transport/TcpConnector.java:74-83`): `mailkit.test.allow_local_mx` system property is honored in production; the MX-mode local-address filter misses IPv6 ULA `fc00::/7` and CGNAT `100.64/10` (`isSiteLocalAddress` only covers RFC 1918 + deprecated `fec0::/10`).

**F20. Library-API polish** —
- `AuthConfig.optionalStrict` (`auth/AuthConfig.java:18`) is read by nothing — dead knob.
- `PeerCertSnapshot.placeholder()` (`tls/PeerCertSnapshot.java:34-38`) is a public method returning `null` as a "marker" — remove before publishing.
- Wither asymmetry: `SmtpConfig` has no `withHost`; `TlsConfig` has no `withSniHost`/`withCipherSuites` (those fields are reachable only via the 12-arg constructor); `XclientConfig` exposes withers for 3 of 13 fields. Builders would serve a public API better than 12/13-arg record constructors.
- `SmtpClient` news up `TcpConnector` and `MxResolver` inline (`SmtpClient.java:113, 554`) even though both classes already have injection seams — no way for library users to plug a resolver/connector (or for tests to cover MX wiring, see T8).
- Javadoc still narrates the internal roadmap ("Phase 2 adds…", "placeholders in Phase 1") — meaningless to external consumers.

**F21. Packaging not publish-ready** — `smtp-client/build.gradle` has no `maven-publish`, no POM metadata/license, no `Automatic-Module-Name` (or `module-info`), no sources/javadoc jars. The artifact would land on Maven Central with an unstable automatic module name derived from the jar filename.

### Informational

**F22. Credential-hygiene claims are best-effort only** — password `char[]`s are zeroed in the clients, but: `Xoauth2AuthClient` builds a `String` from the token (`auth/Xoauth2AuthClient.java:30`), `performAuth` base64-encodes secrets into Strings, and `SmtpTranscript` deliberately retains raw AUTH bytes for the reveal toggle. The `AuthCredentials` Javadoc ("never has to materialize a String") oversells; align the doc with reality. Also `SmtpTranscript.append` invokes the user-supplied listener while holding the transcript monitor — a slow/re-entrant listener stalls the send thread mid-transaction (`SmtpTranscript.java:57-61`).

**F23. Things verified and found sound (positive findings)** — two-layer command-injection defense (`SmtpEnvelope.requireNoLineBreaks` + `writeCommand` CRLF guard, `SmtpClient.java:933-946`); STARTTLS reader replacement discards pre-handshake buffered plaintext (no response-injection window, `Connection.upgrade`); SCRAM iteration-count clamp and `MessageDigest.isEqual`; `verifyCa`/`verifyHostname` decoupling (and the JSSE wrapper does enforce endpoint identification for non-extended trust managers — but see T9 for why this deserves an end-to-end test); PROXY header correctly written before TLS; SIZE=0 treated as unlimited per RFC 1870 §3; normalization carry-state across chunk boundaries; cancellation watcher that closes the socket to unblock parked reads.

---

## Findings — test coverage

Current state: 14 wire-level test classes against a scriptable `FakeSmtpServer` (excellent harness), solid unit tests for the pure helpers, RFC vectors for SCRAM-SHA-1/256, and one Docker/Mailpit integration test. The stop/drop-after matrix (13 phases × stop/drop) is exhaustively covered — exemplary. The gaps cluster on **error paths** and **the auth long tail**.

Ranked by the risk they leave uncovered:

**T1 (High). No failing-TLS-handshake test.** A `FakeSmtpServer` with its self-signed cert + default `verifyCa=true` client would fail the handshake and currently surface `IO_ERROR` — this single test exposes F1 and pins the fix.

**T2 (High). `readResponse` error paths untested:** multi-line code mismatch (`250-` then `550 `), reply line shorter than 3 chars, non-numeric code, invalid separator char, and server-EOF mid-response. These are five cheap `FakeSmtpServer` scripts protecting the protocol core.

**T3 (High). Pipelining failure paths untested:** MAIL rejected inside the batch; all RCPTs rejected (the DATA-response drain at `SmtpClient.java:224-232`); PRDR combined with pipelining. Also add an Exim-style PRDR script (`353` + per-recipient + final, and the single-reply uniform-verdict form) — both fail today (F3) and must accompany its fix.

**T4 (Medium). AUTH wire error paths untested:** 535 rejection → `AUTH_FAILED`; invalid base64 in a 334 challenge; 235 with `isComplete()==false`; `optional` auth skipping when nothing usable is advertised; the "no usable AUTH mechanism" message. Only PLAIN and LOGIN are ever driven through `performAuth` — SCRAM, EXTERNAL, XOAUTH2, OAUTHBEARER have zero wire-level tests (EXTERNAL would immediately expose F6).

**T5 (Medium). `SaslAuthClient` (CRAM-MD5 / DIGEST-MD5) has no tests at all** — not even the RFC 2195 CRAM-MD5 vector (`tim`/`tanstaaftanstaaf` → known digest), which is a 10-line unit test.

**T6 (Medium). `OauthBearerAuthClient` and `ExternalAuthClient` have no unit tests** (XOAUTH2 has one; OAUTHBEARER's kvsep/GS2 framing per RFC 7628 deserves the same).

**T7 (Medium). SCRAM negative paths untested:** tampered server signature (`v=` mismatch), server nonce not echoing the client nonce, malformed server-first, `e=` error in server-final. These are the security-relevant branches of `ScramAuthClient`; vectors only cover success, and the clamp test covers iterations.

**T8 (Medium). MX-routing wiring in `SmtpClient` untested:** MAIL FROM without domain → `CONNECT_FAILED`; empty MX → A/AAAA fallback; `NamingException` → `CONNECT_FAILED`; first-host pick. `MxResolver` itself is well tested, but nothing tests `resolveDestinationHost`, and `TcpConnector`'s MX-mode local-address filter (incl. the system-property bypass) is untested. (Fixing F20's missing injection seam makes these testable without DNS.)

**T9 (Medium). TLS file-based trust/key paths untested:** CA-bundle loading (single PEM, multi-cert PEM, directory), mTLS cert+key loading, cert-without-key error, EC-key failure (F11). Additionally, hostname verification is only asserted at the `SSLParameters` level — there is no end-to-end test that a handshake against a wrong-hostname cert actually fails, which is exactly the guarantee that depends on JSSE wrapping the non-extended `TrustEverythingManager`.

**T10 (Medium). `STARTTLS_OPTIONAL_STRICT` has zero tests**, and `STARTTLS_OPTIONAL` with the server *rejecting* the STARTTLS command (cleartext downgrade, F14) is untested.

**T11 (Low). XCLIENT:** 250-response (continue without re-EHLO), rejected XCLIENT (5xx → `PROTOCOL_VIOLATION`), and optional-skip transcript note are untested (220/re-EHLO, before-STARTTLS, and unadvertised-fatal are covered).

**T12 (Low). ESMTP knob combinations:** `EightBitMimePolicy.NEVER`, `declareSizeOnMail=false`, `honorSize=false`, and SIZE advertised-without-value are untested.

**T13 (Low). `Protocol.SMTP` (HELO) path is never exercised; LMTP untested** (and unimplemented, F10).

**T14 (Low). `PeerCertExtractor` / `PeerCertSnapshot` untested** — SAN collection, SHA-256 fingerprint format, PEM chain rendering; could ride on the existing STARTTLS test by asserting `result.tls()`.

**T15 (Low). Small pure types untested directly:** `MessageSource` (defensive copy of `ofBytes`, `ofPath` size-IOException → empty), `SmtpTranscript.render(true)` reveal path, `SmtpResponse` classification boundaries, `writeCommand` CRLF guard via a hostile `ehloHost`.

**T16 (Low). `ProxyV2Writer` TCP6 payload untested** (v1 TCP6 token and v2 TCP4/LOCAL are covered); address-length-mismatch error path untested.

**T17 (Low). Integration suite is a single PLAIN happy path.** Mailpit supports STARTTLS and AUTH — an integration test for STARTTLS_REQUIRED and one for AUTH PLAIN over TLS would validate the JSSE plumbing against a real implementation rather than the fake.

Per `.agents/testing.md`, each fix above should land with a test that fails on the old behavior — T1–T3 are written to do exactly that for F1/F3.

---

## Prioritized action list

| # | Action | Fixes | Effort |
|---|--------|-------|--------|
| 1 | Catch `SSLException` in `upgradeToTls` → `TLS_FAILED`; add failing-handshake test | F1, T1 | S |
| 2 | Add XOAUTH2/OAUTHBEARER to `isPlaintextOnTheWire` (rename to `exposesCredentialsWithoutTls`) | F2 | S |
| 3 | Rework PRDR reading for 353/uniform-reply; cover Exim-style scripts; fix PRDR+BDAT | F3, T3 | M |
| 4 | Wrap auth challenge loop exceptions into `AUTH_FAILED`; tests for SCRAM negative paths | F5, T7 | S |
| 5 | Emit `=` for empty initial response; wire-test EXTERNAL | F6, T4 | S |
| 6 | MX: try all resolved MX hosts, detect null MX, shuffle equal preference; document sender-domain routing; expose connector/resolver seams; tests | F4, T8, F20 | M |
| 7 | `readResponse` line/length caps | F12 | S |
| 8 | Handle SASL additional-data in 235 (SCRAM server-final) | F7 | M |
| 9 | xtext-encode ENVID/ORCPT/XCLIENT; reject `<>`/space in addresses | F8 | M |
| 10 | Gate or implement LMTP; AUTO-selection credential-kind filter | F10, F9 | M |
| 11 | EC/PKCS#1 key support or explicit error; CA-bundle/mTLS tests; end-to-end hostname-mismatch test | F11, T9 | M |
| 12 | Auth long tail: CRAM-MD5 vector, OAUTHBEARER/EXTERNAL units, 535/bad-base64/optional wire tests | T4–T6 | M |
| 13 | Publishing hygiene: `Automatic-Module-Name`, maven-publish + POM, remove `placeholder()`, dead `optionalStrict`, roadmap Javadoc | F20, F21 | M |
| 14 | Remaining low items: EHLO `AUTH=` folding, OPTIONAL-rejection doc/INFO line, EHLO host literal bracketing, timeout clamp, ULA range, STRICT-mode tests | F13–F19, T10–T16 | M |

*Effort: S ≈ <½ day, M ≈ ½–2 days.*
