# Changelog

## Unreleased

### Added

- Outlook PST and OST archive files are now recognized with distinct icons in the Project view.
- Added a new action to convert Outlook PST and OST archives into a directory tree of standard EML files, complete with configurable duplicate handling, optional message-count limits, and SMTP header extraction.
- The PST converter now seamlessly supports "Highly Encrypted" (Enigma cipher) and "Password Protected" archive files.
- Recipient and sender addresses are now preserved for Exchange-only correspondents: when a message carries no cached SMTP address, the converter keeps the Exchange address (legacyExchangeDN) instead of leaving the field blank.
- Multi-tab support for conversion logging in the MailKit tool window, displaying separate real-time logs for MSG and PST/OST conversions. Added detailed logging for discovered attachments, embedded messages, and reasons for skipped messages.
- Support for extracting `legacyExchangeDN` addresses (e.g. `/O=EXCHANGELABS/OU=EXCHANGE ADMINISTRATIVE GROUP...`) from PST/OST archives and rendering them into EML headers.
- PST/OST conversion can now recover messages the normal folder walk misses: soft-deleted items still attached to a folder are written into a `Recovered Items` folder, and fully detached (orphaned) message nodes into an `Orphaned Items` folder. Both are enabled by default and can be turned off in the conversion dialog.
- PST/OST conversion now exports calendar items: appointments and meeting requests are written as EML with an attached calendar invite (`invite.ics`) carrying the start/end time and location, instead of being silently skipped.

### Changed

- All MailKit context-menu actions (Convert to EML, Send EML, Save Attachment) now feature the standard MailKit EML icon for better visibility and consistency.

### Fixed

- Converting a malformed or malicious PST/OST archive no longer crashes the IDE: deeply nested folder trees, corrupt or oversized internal structures, and messages with an excessive number or size of attachments are now skipped cleanly with a logged warning instead of aborting the whole conversion.
- Converted EML files are now hardened against header injection — control characters smuggled into a message's email addresses, Message-ID, or attachment metadata can no longer add or spoof headers in the output.
- The `Date:` header of a converted message now reflects the original submission time rather than the delivery time, in line with RFC 5322.
- Folders whose names match reserved Windows device names (`CON`, `NUL`, `COM1`, …) or end in a dot or space are now safely renamed so they extract correctly on Windows.
- PST/OST conversion now reliably extracts every message from large folders; previously some messages in folders with very large contents tables could be silently dropped.
- Inline images referenced from an HTML body (`cid:`) now display in mail clients: such parts are grouped with the body in a `multipart/related` section instead of being appended as separate attachments.
- Recipients are no longer silently lost when a message's recipient table cannot be read — the `To:`, `Cc:`, and `Bcc:` headers now fall back to the stored display names.
- More message code pages are decoded correctly (Japanese, Korean, Chinese, and ISO-2022 encodings), reducing garbled text in converted messages.
- Inline images in converted MSG files now display in mail clients: the attachment's `Content-ID` is preserved so `cid:` references in the HTML body resolve.
- International attachment filenames now follow RFC 2231 (`filename*=UTF-8''…`), so mail clients such as Apple Mail and Thunderbird show the correct name instead of a raw encoded string.
- Converting an MSG whose stored internet headers contain non-ASCII characters no longer aborts the conversion.
- RTF-only message bodies now honor the document's code page (instead of always assuming Windows-1252) and respect the `\uc` Unicode skip count, reducing garbled non-Western text in the plain-text fallback.
- Very deeply nested embedded MSG messages now truncate with a placeholder instead of failing the entire conversion, matching the PST/OST behavior.
- A failed conversion no longer leaves a truncated `.eml` behind: output is written to a temporary file and moved into place only on success.
- PST/OST archives whose folder hierarchy references itself (corrupt or hostile input) no longer loop forever; already-visited folders are skipped.
- Conversion failures (unreadable folders, messages, or recipients) are now reported in the PST/OST conversion log instead of only the IDE log, so problems are visible while converting.
- Converted messages now always carry a `Date` header, even when the source message stored neither a send nor a delivery time (required by RFC 5322).
- When a message's stored internet headers are present but incomplete, MailKit now fills in any missing `From`, `To`, `Cc`, `Bcc`, `Subject`, or `Date` from the MAPI properties instead of dropping them.
- Outlook messages whose body is stored only as HTML-encapsulated RTF now convert to a proper HTML body instead of a plain-text approximation that lost the markup.
- PST/OST export keeps generated file paths within the Windows 260-character limit by trimming long subjects while preserving a unique filename.
- A malformed or non-Outlook `.msg` file now fails with a clear conversion error instead of a raw library exception.
- Converting an Outlook appointment `.msg` no longer attaches a misleading, empty calendar invite (placeholder "now" start/end times and no location); the appointment's subject, body, and date are still exported as a normal email.

### Security

- Generated EML files can no longer be written outside the chosen output directory when a PST folder carries an unusual name such as `.` or `..`.

## 1.1.3 - 2026-05-31

### Added

- EML headers containing RFC 2047 encoded words (e.g. `=?UTF-8?Q?Hello?=\`) are now
  automatically folded into their decoded, human-readable text. Clicking the
  folded text expands it back to the raw encoded format.

### Changed

- MailKit settings are now cleanly organized under a single `Tools → MailKit` parent
  node. The generic settings (syntax highlighting, attachments) are nested under
  `Tools → MailKit → General`, and SMTP profile settings are nested under
  `Tools → MailKit → SMTP`.

### Performance

- Typing in large `.eml` files no longer lags. Messages whose attachment is a
  single multi-megabyte base64 line used to stutter on every keystroke because
  each edit re-scanned and re-copied the entire attachment; editing such files
  (and large message bodies in general) now stays responsive.
- *Save Attachment As…* and *Open Attachment with System App* now decode straight
  to the target file instead of building the whole decoded payload in memory
  first, so saving or opening a very large attachment uses far less memory. The
  *Send EML* dialog likewise reads only the first part of the source file when
  filling in its From / Subject preview, so opening it on a huge `.eml` no longer
  pulls the entire message into memory on the UI thread.
- Converting large Outlook `.msg` files to `.eml` now streams the output directly
  to disk. This resolves memory exhaustion and IDE freezes when converting messages
  with massive attachments.
- The *Send EML* dialog now opens instantly even when triggered on a multi-gigabyte
  `.eml` file. The file's payload is now loaded on a background thread instead of
  blocking the IDE's user interface.

### Fixed

- Converting an Outlook `.msg` file to `.eml` now faithfully preserves all original internet transport headers (such as `Received` traces, spam scores, and DKIM signatures) instead of silently dropping them. It also successfully extracts `Bcc` recipients and preserves multiple message bodies (like both HTML and Plain Text) in a standard `multipart/alternative` layout rather than throwing away all but one body.
- The list of headers in the Color Scheme settings now updates immediately when a new 
  custom header is added and applied. A UI note was added to clarify that the demo text 
  preview itself requires reopening the Settings dialog to reflect new headers, due to 
  IntelliJ platform UI caching limitations.
- The *Send EML* dialog's *Password (one-time)* field is now genuinely one-time:
  the password you type is used for that single send only and is no longer written
  to stored credentials, where it previously overwrote the profile's saved
  password.
- The *Send EML* dialog's MX routing feature can no longer be coerced into
  connecting to local or internal networks. When MX routing is enabled,
  connections to loopback and site-local IP addresses are now blocked, preventing
  a malicious message from probing internal services.
- The *Send EML* dialog now rejects carriage returns, line feeds, and null bytes
  in a configured PROXY protocol source or destination IP address, preventing an
  attacker from injecting extra SMTP commands through a malformed connection profile.
- Opening or editing an `.eml` file with malformed MIME boundary declarations
  no longer causes the IDE to freeze or run out of memory. Boundary strings are
  now capped at a reasonable length, preventing pathological files with missing
  newlines from exhausting system resources during parsing.
- An SMTP server can no longer pin the *Send EML* thread in a runaway password
  computation. A hostile or buggy server advertising an absurd SCRAM iteration
  count is now rejected up front instead of burning CPU on key derivation.
- The *Verify CA chain* checkbox in an SMTP profile now actually takes effect.
  Unchecking it previously did nothing — the server certificate chain was still
  validated against the system trust store — so the control was misleading.
  Clearing it now relaxes CA-chain validation as intended, while hostname
  verification stays governed by its own setting.
- Converting a malformed or corrupt Outlook `.msg` file now shows a clean
  "Could not convert…" notification instead of an IDE "internal error" report.
  Failures that previously slipped through — a truncated or hand-tampered file,
  or a message whose address carries non-ASCII text — are now reported the same
  way as any other conversion error.
- The *Send EML* dialog no longer lets an envelope address smuggle extra SMTP
  commands onto the connection. An *Envelope From* / *To* (or DSN ORCPT / ENVID)
  value containing a carriage return or line feed is now rejected up front with a
  clear error, instead of being written verbatim into the `MAIL FROM:` /
  `RCPT TO:` line where a server would interpret the trailing text as additional
  commands.
- Opening or editing an `.eml` whose MIME parts are nested thousands of levels
  deep (deeply chained `multipart/*` or `message/rfc822`) no longer risks
  overflowing the parser and erroring out. Nesting is now capped at a generous
  depth and anything beyond it is shown as plain body text, so even a
  hand-crafted, pathologically nested message stays editable.
- *Open Attachment with System App* now asks for confirmation before handing an
  executable, script, or other active-content file (for example
  `invoice.pdf.exe` or a `.html` attachment) to the operating system. Ordinary
  documents such as PDFs and images still open without a prompt.
- The *Send EML* dialog now warns before sending over a connection that is not
  guaranteed to be encrypted — TLS mode *None* (cleartext) or an *optional*
  STARTTLS mode a server can silently downgrade — so an unprotected send is a
  deliberate choice rather than a surprise. Profiles using required STARTTLS or
  TLS-on-connect send without a prompt.
- Cancelling an SMTP send now takes effect immediately, even while the client is
  blocked waiting on the server, instead of only after the connection timeout
  elapses.
- Converting an Outlook `.msg` whose RTF-only body carries an out-of-range
  Unicode escape no longer fails with an IDE internal error; the invalid
  character is skipped and conversion completes.
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
  - Live tool window: *MailKit* streams every wire byte as it's sent,
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
