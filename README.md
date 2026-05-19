# MailKit

An IntelliJ IDEA plugin that brings first-class support for `.eml` (Email Message format) files — syntax highlighting, code folding, and configurable header coloring.

<!-- TOC -->
## Context
* [Features](#features)
* [Requirements](#requirements)
* [Installation](#installation)
  * [From source](#from-source)
* [Development](#development)
* [Usage](#usage)
* [Contributing with AI](#contributing-with-ai)
* [Reporting Issues](#reporting-issues)
* [Feature Requests](#feature-requests)
* [Donate](#donate)
* [License](#license)
<!-- TOC -->
## Features

- **Syntax highlighting** — Headers, MIME boundaries, and body content are visually distinct, including per-part MIME headers and nested `message/rfc822` attachments
- **MIME part folding** — Collapse multipart boundaries to focus on the section you care about
- **Per-header color customization** — Assign individual colors to headers like `From`, `Subject`, `Date`, etc. via **Settings > Editor > Color Scheme > MailKit**
- **Name-only highlighting** — Optionally highlight just the header name (e.g. `Subject:`) instead of the full line, configurable per header
- **Global highlighting toggle** — Disable all EML highlighting at once via **Settings > Editor > MailKit**
- **Configurable header list** — Add or remove which headers get custom highlighting via **Settings > Editor > MailKit**
- **Save attachments** — A gutter icon next to any attachment MIME part (or `Save Attachment As…` / `Open Attachment with System App` in the editor context menu) decodes the part body (base64, quoted-printable, 7bit/8bit, binary) and writes it to disk or hands it to the OS. Toggle via **Settings > Editor > MailKit > Show attachment save actions**.
- **MSG → EML conversion** — Right-click any Outlook `.msg` file in the Project view and choose **Convert to EML** to produce a standards-compliant `.eml` next to the source and open it in the editor. Handles HTML/text/RTF bodies, attachments, recursively-converted embedded `.msg` messages, and RFC 2047 encoding for non-ASCII headers.
- **EML inspections & quick-fixes** — RFC 5322 / MIME violations are flagged inline with Alt+Enter fixes. Configurable individually under **Settings > Editor > Inspections > MailKit**:
  - `MissingRequiredHeader` — `From` or `Date` absent on the message root
  - `LineTooLong` — line exceeds 998 octets (RFC 5322 §2.1.1)
  - `UnterminatedBoundary` — declared multipart boundary never closes with `--X--`
  - `BoundaryNeedsQuoting` — boundary value contains tspecial characters but is unquoted
  - `UnencodedNonAsciiHeader` — non-ASCII bytes in a structured header outside an RFC 2047 encoded-word
  - `InvalidBase64Body` — base64 part body contains characters outside the RFC 4648 alphabet
  - `CharsetMismatch` — declared `charset=` cannot decode the actual body bytes
  - `UnparseableDate` — `Date:` value does not parse per RFC 2822
  - `DuplicateMessageId` — header block contains more than one `Message-ID`
  - `UnknownContentTransferEncoding` — CTE value outside `7bit / 8bit / binary / quoted-printable / base64`
- **Sending EML** — a swaks-equivalent embedded SMTP client. Right-click an `.eml` file in the editor or project view and choose **Send EML…** to push the file's bytes through a configured server. Highlights:
  - **STARTTLS, TLS-on-connect, mTLS** — full JDK-native TLS surface incl. custom CA bundles, client certs, SNI, hostname-verify toggles, peer-cert capture
  - **SASL AUTH** — `PLAIN`, `LOGIN`, `CRAM-MD5`, `DIGEST-MD5`, `SCRAM-SHA-1`, `SCRAM-SHA-256`, `EXTERNAL`, `XOAUTH2`, `OAUTHBEARER`; plaintext mechanisms are refused over non-TLS sockets unless the profile explicitly opts in
  - **ESMTP extensions** — `PIPELINING`, `BDAT`/`CHUNKING`, `PRDR`, `SIZE` preflight, `SMTPUTF8`, `8BITMIME` (require / downgrade / never), full DSN (`NOTIFY`, `ORCPT`, `ENVID`, `RET`)
  - **Relaying primitives** — Postfix `XCLIENT` (with before-STARTTLS toggle) and HAProxy PROXY protocol v1/v2 written before the banner
  - **Transport knobs** — IP family selection (auto / v4 / v6), local-interface / local-port bind, DNS MX routing from the `MAIL FROM` domain (swaks `--copy-routing`)
  - **Per-phase stop / drop** — every swaks `--quit-after` / `--drop-after` phase is honoured: `CONNECT`, `BANNER`, `FIRST_HELO`, `STARTTLS`, `TLS`, `HELO`, `AUTH`, `MAIL`, `RCPT`, `DATA`, `BDAT`, `DOT`, `QUIT`
  - **Profiles + credentials** — saved under **Settings > Tools > MailKit SMTP**; passwords + TLS key passphrases go through IntelliJ's `PasswordSafe`, never the settings XML
  - **Per-profile default headers** — each profile carries a *Default Headers* table (seeded with `From`, `To`, `Cc`, `Bcc`) whose values pre-fill the Send EML dialog's envelope fields; rows can be edited, added, or removed freely
  - **Live console tool window** — every wire byte streams into the *MailKit SMTP* tool window as it's sent, with AUTH lines redacted by default
  - **Per-project audit log** — successes + failures recorded to `<project>/.idea/mailkit/smtp-log.json` (no credentials, no message bytes); inspect via **Tools > Show Recent SMTP Sends…**
  - **Egress toggle** — a global checkbox at the top of the SMTP settings page hides every Send action when off

## Requirements

- IntelliJ IDEA 2025.3+

To build from source you'll need a JDK 21 toolchain — Gradle auto-provisions it via Foojay if absent.

## Installation

### From source

```bash
git clone https://github.com/Ttereshchenko/mailkit.git
cd mailkit
./gradlew buildPlugin
```

The plugin zip will be in `build/distributions/`. Install it via **Settings > Plugins > Install Plugin from Disk**.

### Development

```bash
./gradlew runIde         # Launch a sandboxed IDE with the plugin loaded
./gradlew compileJava    # Compile only
./gradlew test           # Run tests
./gradlew verifyPlugin   # Run JetBrains plugin verifier (compatibility check)
./gradlew check          # Run tests + Checkstyle + Spotless
./gradlew spotlessApply  # Auto-fix formatting and unused imports
./gradlew spotlessCheck  # Verify formatting without modifying files
```

## Usage

1. Open any `.eml` file in IntelliJ IDEA
2. Headers, boundaries, and body are automatically highlighted
3. MIME parts can be collapsed/expanded using the gutter fold icons
4. Customize header colors in **Settings > Editor > Color Scheme > MailKit**
5. Configure which headers are highlighted and toggle per-header name-only mode in **Settings > Editor > MailKit**
6. Click the save icon in the gutter next to an attachment to decode and write it to disk, or right-click an attachment part for *Save Attachment As…* / *Open Attachment with System App*
7. Right-click any `.msg` file in the Project view and choose *Convert to EML* to convert it to a standards-compliant `.eml` and open the result
8. Configure SMTP profiles in **Settings > Tools > MailKit SMTP**, then right-click any `.eml` file and choose *Send EML…* to push it through the configured server. Wire-level activity streams into the *MailKit SMTP* tool window; past sends are catalogued under *Tools > Show Recent SMTP Sends…*

## Contributing with AI

This project is configured for AI-assisted development using [Claude Code](https://claude.ai/code).

The [`AGENTS.md`](AGENTS.md) file is the entry point — it links to focused guidance files under `.agents/` covering project architecture, build commands, workflow rules, and code style. Any AI agent (Claude Code or compatible tool) will pick these up automatically.

To get started:

1. Install [Claude Code](https://claude.ai/code)
2. Open the project root in your terminal
3. Run `claude` — the agent will load `AGENTS.md` and all sub-files automatically

## Reporting Issues

Detailed bug reports help us diagnose and fix problems faster. When filing an issue, providing the context below lets us reproduce the problem locally and cuts down the back-and-forth.

If you encounter a bug or unexpected behavior, please [open a GitHub issue](https://github.com/Ttereshchenko/mailkit/issues) with the following information:

1. **Steps to reproduce** — numbered steps to trigger the issue
2. **EML file** — attach or paste the `.eml` file that causes the problem (please redact any sensitive data)
3. **IDE specification** — IDE name, version, and OS (found via **Help > About**)
4. **Plugin version** — MailKit version (found via **Settings > Plugins**)

## Feature Requests

Have an idea for a new feature? We'd love to hear it! Please [open a GitHub issue](https://github.com/Ttereshchenko/mailkit/issues) with the **feature request** label and include:

- A description of the desired behavior
- The use case or motivation — how would this feature improve your workflow?

## Donate

If MailKit saves you time, consider buying me a coffee — it helps keep the project going.

[![Buy Me a Coffee](https://img.buymeacoffee.com/button-api/?text=Buy%20me%20a%20coffee&emoji=&slug=ttereshchenko&button_colour=FFDD00&font_colour=000000&font_family=Cookie&outline_colour=000000&coffee_colour=ffffff)](https://www.buymeacoffee.com/ttereshchenko)

## License

[Apache License 2.0](LICENSE)
