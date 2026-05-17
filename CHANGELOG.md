# Changelog

## Unreleased

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
