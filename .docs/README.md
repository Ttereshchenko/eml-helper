# Internal docs

Internal reference material for this repository.

These files are **not** auto-loaded into any agent's context. An agent (or a
human) reads from here only when it's directly relevant to the task at hand —
see the trigger line in [`AGENTS.md`](../AGENTS.md).

## `rfc/` — email RFC corpus

Full text of the email-related RFCs, grouped by topic. These are **large
reference files** (e.g. `05-mail-retrieval/rfc9051.txt` is ~8,700 lines).

**Read discipline — this is the whole point of the folder:**

- Never read a whole RFC into context. Use `grep`/search to jump to the
  relevant section, then read only the surrounding lines.
- Pick the single RFC for the topic from the index below; don't scan the
  directory or open multiple RFCs speculatively.
- Cite as `rfc<NNNN> §<section>` when you rely on a passage.

### Topic index

| Folder | Covers | Key RFCs |
|---|---|---|
| `01-message-format` | Message/header syntax | rfc5322 (rfc2822/822), rfc4021, rfc6854 |
| `02-mime` | MIME structure & encodings | rfc2045–2049, rfc2183 |
| `03-smtp` | SMTP & extensions | rfc5321 (rfc2821/821), rfc1870, rfc3030, rfc3463 |
| `04-message-submission` | Submission (port 587) | rfc6409 |
| `05-mail-retrieval` | IMAP / POP3 | rfc9051, rfc3501, rfc1939 |
| `06-authentication` | SPF / DKIM / DMARC / ARC | rfc7208, rfc6376, rfc7489, rfc8617 |
| `07-transport-security` | STARTTLS / MTA-STS / TLS-RPT | rfc3207, rfc8461 |
| `08-internationalized-email` | EAI / SMTPUTF8 | rfc6530–6533 |
| `09-notifications-reporting` | DSN / MDN / ARF | rfc3461–3464, rfc8098 |
| `10-encrypted-content` | S/MIME / PGP | rfc8551, rfc3156 |
| `11-foundational` | ABNF, key words, DNS | rfc5234, rfc2119/8174, rfc1035 |

Confirm exact filenames with `ls .docs/rfc/<folder>/` before opening.