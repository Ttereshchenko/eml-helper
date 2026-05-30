# Changelog

## Unreleased

### Performance

- Typing in large `.eml` files no longer lags. Messages whose attachment is a
  single multi-megabyte base64 line used to stutter on every keystroke because
  each edit re-scanned and re-copied the entire attachment; editing such files
  (and large message bodies in general) now stays responsive.

### Fixed

- Quoted-printable decoding no longer corrupts literal (non-escaped) characters
  above `U+00FF` in an attachment body. The non-ASCII fallback re-encoded each
  char with ISO-8859-1, which collapses anything past Latin-1 to `?`; it now
  uses UTF-8 so code points like `ł` survive *Save Attachment As…*.
- Header highlighting could go stale or render the wrong colors under non-ASCII
  locales and external state mutation. `EmlHeaderSettings` now hands out
  immutable copies of its header lists (so the case-insensitive lookup cache
  can't be desynced), marks the highlighting-enabled flag `volatile` (it is read
  on the background lexer thread while the EDT can toggle it), and derives color
  keys with `Locale.ROOT` (so `Title`/`title` no longer diverge under `tr_TR`).
- A hand-edited or imported `emlHeaderSettings.xml` containing an invalid header
  name (e.g. one with `<` or `&`) can no longer break the Color Scheme preview.
  Header names are now validated against the same pattern as the settings UI
  when state loads, so malformed entries never reach the demo-text generator.
- The *Settings → Editor → MailKit* page no longer leaks its Swing component
  tree across dialog reopens. The configurable now releases its table, models,
  and panels in `disposeUIResources` instead of only stopping cell editing.
- MIME-part folding, header highlighting, and the attachment gutter icon
  no longer disappear while the IDE is indexing ("dumb mode"). The folding
  builder, header annotator, and attachment line-marker provider now
  implement `DumbAware` — without it the platform skipped these
  index-free extensions during indexing, so opening an `.eml` (or
  reindexing) briefly stripped its decorations. Lexer-based boundary
  syntax coloring was unaffected and is unchanged.
- `EmlLexer` now unfolds `Content-Type` across continuation lines when
  deciding whether a part is `message/rfc822`. Previously only the first
  physical line of the header was inspected, so RFC 5322-folded values like
  `Content-Type:\n message/rfc822` — which real MUAs do emit — were missed
  and the inner RFC 822 message was mis-lexed as a flat body, losing
  highlighting and folding for the nested headers.
- `EmlBoundaryParser` now preserves internal whitespace inside a quoted
  `boundary="…"` value. The previous regex excluded whitespace from its
  capture group in both branches, so RFC 2046-legal values like
  `boundary="ab cd"` were captured as just `ab` — the real `--ab cd`
  marker lines then went unrecognised and the whole multipart lexed as a
  flat body with no part folding or per-part header highlighting.

## 1.1.2 - 2026-05-27

### Performance

- `EmlLexer` no longer rescans the full document on every restart. The
  boundary table returned by `EmlBoundaryParser.collect` is now cached by
  buffer identity, so incremental relex / rehighlight passes on large
  multipart EMLs stop running an O(N) scan on the EDT for every keystroke.
- Attachment detection no longer materializes the part body when the
  gutter line-marker pass or an action's `update()` call asks "is this a
  qualifying attachment?". `AttachmentDetector.detect` used to call
  `PsiFile.getText()` (a full file copy) and slice it for every candidate
  part; it now captures the body offsets and resolves the body lazily —
  through the file's `CharSequence` view, allocating one body-sized
  string — only when the user actually invokes *Save Attachment As…* or
  *Open with System App*, and only on the background task. Decoding for
  both actions also moved off the EDT.

### Fixed

- `EmlBoundaryParser` no longer harvests `boundary=…` substrings from body
  lines (prose, base64 payloads, forwarded EMLs quoted in a `text/plain`
  part) — only `Content-Type` headers contribute to the boundary set, so
  later body lines that happen to spell `--phantom` / `--phantom--` are
  no longer mis-tokenized as boundary markers. When the actual boundary
  string appears literally inside a `text/*` part body, the structural
  close is now resolved to the **last** matching `--<name>--` line in the
  document, so the multipart is not terminated prematurely on a quoted
  occurrence.
- *Open Attachment with System App* no longer stages every attachment to
  the fixed path `<IDE temp>/mailkit/<filename>`. Two attachments that
  shared a filename (e.g. two `report.pdf` parts) could overwrite each
  other, letting the user open one file and see another's bytes; the
  directory also accumulated forever across IDE sessions. Each invocation
  now writes to its own random `<IDE temp>/mailkit-attachments/open-XXXX/`
  directory, the staging dir is deleted on IDE shutdown, and stale
  leftovers from prior (crashed) sessions are swept on next use.

### Changed

- SMTP profile dialog redesigned around the documented tab grouping
  (`smtp-profile-config-groups.md`):
  - **7 tabs**: Connection · **Security / TLS** · Auth · **ESMTP
    extensions** *(only when protocol = ESMTP)* · **Transport / Network**
    · **Relay framing** · **Envelope**.
  - **Dynamic reveals**: TLS verify / CA / advanced rows appear with
    `tlsMode ≠ NONE`; Auth fields collapse when mechanism is
    `DISABLED`; the password row becomes "Access token" for `XOAUTH2` /
    `OAUTHBEARER`; the ESMTP-extensions tab is greyed out for
    `SMTP` / `LMTP` protocols; PROXY source/dest fields grey out when
    version = `NONE`; individual XCLIENT attributes grey out when the
    raw-command escape hatch is set.
  - **PROXY protocol (v1 / v2)** and **XCLIENT** relay framing are now
    persisted per profile, replacing the prior code-only configuration.
  - **Newly surfaced profile knobs** (previously model-only or
    code-only): connection `timeoutSeconds`; TLS `hostnameOverride` /
    `sniHost` / `clientCert` / `clientKey` / `clientChain` paths; TLS
    `protocols` / `cipherSuites` lists; Auth `authzId` /
    `authOptional` / `authOptionalStrict`; ESMTP `enforceSmtpUtf8` /
    `honorSize` / `eightBitMime` policy / `declareSizeOnMail`.

### Removed

- The *Default Headers* tab is gone — replaced by the **Envelope** tab
  with two fields (`From`, `To`). The Send EML dialog drops its
  per-send `Cc` / `Bcc` rows; recipients come from the envelope `To`
  field (comma-separated).

## 1.1.1 - 2026-05-19

### Added

- SMTP profiles now have a **Default Headers** tab — a freely editable
  `Header Name` / `Value` table pre-seeded with `From`, `To`, `Cc`, `Bcc`.
  The *Send EML* dialog populates its envelope `From / To / Cc / Bcc`
  fields from the active profile's default headers (replacing the prior
  behaviour of parsing them out of the EML file).

### Changed

- New SMTP profiles default to the `ESMTP` protocol instead of `SMTP`,
  matching `SmtpConfig.defaults()`.

## 1.1.0 - 2026-05-18

### Added

- **Send EML** — embedded swaks-equivalent SMTP / ESMTP / LMTP client.
  Right-click an `.eml` file in the editor or project view and choose
  *Send EML…* to push the file's bytes through a configured server with
  per-send envelope override.
  - Wire client: STARTTLS, TLS-on-connect, mTLS with custom CA bundle / SNI /
    hostname-verify, JDK-native SASL (PLAIN, LOGIN, CRAM-MD5, DIGEST-MD5,
    EXTERNAL, SCRAM-SHA-1, SCRAM-SHA-256, XOAUTH2, OAUTHBEARER); plaintext
    mechanisms refused over non-TLS sockets unless explicitly overridden.
  - ESMTP extensions: PIPELINING, CHUNKING / BDAT, PRDR, SIZE preflight,
    SMTPUTF8 enforcement, 8BITMIME policy (require / downgrade / never),
    DSN with NOTIFY / ORCPT / ENVID / RET on MAIL FROM and RCPT TO.
  - Relaying primitives: Postfix XCLIENT (with before-STARTTLS toggle and
    raw-command escape hatch) and HAProxy PROXY protocol v1 ASCII / v2
    binary, written before any SMTP byte.
  - Transports: TCP only — IP family AUTO / IPv4 / IPv6, local-interface /
    local-port bind, DNS MX routing from the `MAIL FROM` domain.
  - Per-phase abort matrix: every swaks `--quit-after` / `--drop-after`
    target is honoured (CONNECT / BANNER / FIRST_HELO / STARTTLS / TLS /
    HELO / AUTH / MAIL / RCPT / DATA / BDAT / DOT / QUIT).
  - Profiles + credentials: persisted under *Settings → Tools → MailKit SMTP*
    with PasswordSafe-backed passwords + TLS key passphrases. Global egress
    toggle hides every Send action when off.
  - Live tool window: *MailKit SMTP* streams every wire byte as it's sent,
    color-coded by direction, with AUTH lines redacted by default.
  - Per-project audit log: `.idea/mailkit/smtp-log.json` records every send
    (no credentials, no message bytes); inspect via *Tools → Show Recent
    SMTP Sends…*.

  This feature requires a dedicated security review before its release.
- **MSG → EML conversion** — `.msg` files (Outlook OLE2 compound documents)
  are recognised in the Project view with a distinct icon. Right-click →
  *Convert to EML* writes `<name>.eml` next to the source and opens it in the
  editor. Header mapping covers `From`, `To`, `Cc`, `Subject`, `Date`, and
  `Message-ID`; body selection prefers HTML → plain text → stripped RTF;
  attachments are emitted as `multipart/mixed` parts; embedded `.msg`
  attachments recurse into the converter and are attached as `message/rfc822`.
  Non-ASCII header values are RFC 2047 Base64-encoded (`=?UTF-8?B?...?=`).
  Runs on a cancellable background task with progress and surfaces I/O or
  malformed-MSG errors as balloon notifications.

## 1.0.0 - 2026-05-16

### Added

- **EML inspections** — 10 LocalInspectionTools surface RFC 5322 / MIME
  violations (missing required headers, line too long, unterminated MIME
  boundary, unquoted boundary, unencoded non-ASCII headers, base64 alphabet
  errors, charset mismatch, unparseable Date, duplicate Message-ID, unknown
  Content-Transfer-Encoding) with Alt+Enter quick-fixes. Configurable under
  *Settings → Editor → Inspections → MailKit*; enabled by default at
  WARNING severity.
- **Save Attachment To Disk** — decode a MIME attachment (base64,
  quoted-printable, 7bit/8bit, binary) and either save it to a chosen
  location via *Save Attachment As…* or hand it to the OS handler via
  *Open Attachment with System App*. Available from the editor context
  menu and as a gutter icon on any qualifying MIME part (Content-
  Disposition: attachment, a `name=` / `filename=` parameter, or a
  non-text non-multipart media type). Toggle via *Settings → Editor →
  MailKit → Show attachment save actions*.

### Fixed

- Color Scheme preview now refreshes its demo text after a new custom header
  is added via *Settings → MailKit → Apply*. Previously, the descriptor tree
  updated but the preview editor kept the stale sample text, so users could
  not see how their colour choice would render for the freshly added
  header.
- *Settings → Editor → MailKit* layout refreshed. Settings are grouped into
  "Headers" and "Attachments" sections, the "Name Only" column header is no
  longer truncated, and header names in the table can now be edited inline
  (double-click a row) with the same validation as adding a new header.

## 0.9.0 - 2026-05-15

### Changed

- Restructured EML parsing around a real PSI tree (headers, header blocks, MIME parts, nested
  `message/rfc822` messages). Folding now follows the structural tree instead of name-based
  boundary pairing, which removes a class of edge-case issues with nested MIME parts.
- Header annotator now resolves the header name via the parent `EmlHeader` element rather than
  walking PSI siblings, removing an O(n) cost on long folded headers.
- Content-Type parsing handles folded continuations and quoted/escaped parameter values.

### Added

- *Color Scheme → MailKit* preview now includes a sample line for each
  user-configured custom header, so colors picked for custom headers can be
  previewed before clicking *Apply*.
- RFC 2047 encoded-word decoding for header values (`=?UTF-8?Q?…?=` / `=?…?B?…?=`).
- Test coverage for `EmlSyntaxHighlighterFactory`, the RFC 2047 decoder, the PSI parser,
  and edge cases: missing terminator boundaries, nested `message/rfc822` with CRLF,
  BOM-prefixed content, and RFC 2047 encoded subjects.

### Fixed

- "Add Header" dialog in *Settings → MailKit* now accepts custom header names
  (e.g. `X-Custom`, `CUSTOM-HEADER`); previously it was a non-editable dropdown
  restricted to 15 predefined headers.
- Header highlighting now covers per-part MIME headers and nested `message/rfc822` attachments (#27)
- `EmlBoundaryParser` now logs duplicate boundary declarations at debug level instead of
  silently merging them.
- Newly added headers now appear in the Color Scheme page immediately after Apply, without
  needing to reopen the Settings dialog.

## 0.0.1 - 2026-05-14

### Added

- Syntax highlighting for EML headers, MIME boundaries, and body content
- MIME part code folding — collapse and expand nested message parts
- Per-header color customization via the color scheme editor
- Name-only highlighting mode (highlight header name, not value)
- Global highlighting toggle under Settings → Editor → EML
- Custom header list support — add or remove headers to highlight
