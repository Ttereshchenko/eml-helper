# Changelog

## Unreleased

### Fixed (editor highlighting)

- EML header and MIME-boundary highlighting now shows its colors on every editor color scheme. On themes other than the classic Default/Darcula — including the New UI Light/Dark and Islands Dark/Light schemes — headers had been rendering in plain gray; each highlight now falls back to a sensible default color so it is visible everywhere (including on a fresh install), while the bundled Default/Darcula palettes are unchanged.
- A stray empty line at the very top of an `.eml` file, or between header lines, no longer switches off header highlighting for everything below it. Header coloring now tolerates blank lines in the top-level header block and keeps highlighting the headers that follow, instead of treating the first blank line as the start of the message body.

## 1.2.2 - 2026-06-18

### Fixed (conversion charset fidelity)

- Attachment file names written in a non-Latin code page (Cyrillic, Greek, CJK, …) in a legacy `.msg` now keep their original characters instead of turning into garbled text — they are decoded with the message's own code page rather than always Western European.
- A `.msg` plain-text body stored as UTF-8 is now decoded as UTF-8 instead of being mangled into mojibake.
- Clear-signed S/MIME messages converted from `.msg`/`.pst`/`.ost` keep their signature verifiable even when the signed content contains 8-bit (non-ASCII) bytes: the original bytes are now written through verbatim instead of being silently altered.
- An RTF body preserved as a `body.rtf` attachment from `.pst`/`.ost` now carries the source RTF bytes exactly, with no rare byte substitutions.
- An RTF body preserved as a `body.rtf` attachment from a `.msg` now carries the source RTF bytes exactly too — the same guarantee, without the rare byte substitutions the previous Western-European round-trip could introduce.
- Personal distribution-list member names and reply-to names in a legacy `.msg` written in a non-Latin code page now keep their original characters, decoded with the message's own code page instead of always Western European (the same fix attachment file names received).
- Converting an RTF body written in a multi-byte code page (CJK, or UTF-8) from a `.msg`, or via the RTF→plain-text fallback used for `.pst`/`.ost`, no longer garbles its text: a character's consecutive bytes are now decoded together instead of one at a time (which turned each character into replacement/`?` marks).

### Fixed (conversion correctness)

- Exchange recipients in a converted `.msg` that have no cached SMTP address now keep their full Exchange (X.500) address in `To`/`Cc`/`Bcc`, meeting attendees and bounce reports, instead of a truncated fragment that no client can resolve — the `.pst`/`.ost` conversion already kept the full address.
- Bounce messages and delivery/read receipts converted from `.pst`/`.ost` now carry the proper machine-readable `Status` code (with the human-readable transport text moved to `Diagnostic-Code`) and name the recipient that actually failed, matching the `.msg` conversion.
- A recurring appointment whose stored recurrence pattern is corrupt (an empty day-of-week set) is now exported as a single occurrence instead of producing an unparseable calendar recurrence rule.
- A signed (S/MIME) message attached *inside* another converted message now keeps its signature verifiable too: the byte-exact guarantee that already protected a top-level signed message now extends to a signed message forwarded or embedded as an attachment, which previously had its 8-bit signed bytes altered (breaking the signature) when its parent was converted.
- Recipients of a resent or saved sent `.msg` are now sorted into `To`/`Cc`/`Bcc` correctly and keep their addresses: a recipient that Exchange had tagged as already-processed (a high-order flag on the recipient type) is no longer dropped to a names-only fallback that lost the real SMTP address and collapsed the To/Cc/Bcc split.
- A converted delivery report whose stored diagnostic already names its own type (e.g. `smtp; 550 …`) no longer doubles the type token (`smtp; smtp; …`), and a non-canonical enhanced status code with leading zeros (`5.01.001`) is normalized to its standard form (`5.1.1`).
- Rewriting an HTML body's charset declaration to UTF-8 no longer disturbs an unrelated attribute that merely ends in `charset` (such as `data-charset`); only the genuine `<meta charset=…>` / `http-equiv` declaration is changed.
- A converted `.msg` now stamps the `Date:` header with when the message was sent (its origination time) rather than when it was delivered, matching the `.pst`/`.ost` conversion and the standard meaning of `Date:`.
- A `.pst`/`.ost` message sent only on another person's behalf — with no separate sender address of its own — now keeps that author in `From:` instead of collapsing to an `undisclosed` placeholder.
- Recipients of a resent or saved sent `.pst`/`.ost` message are now sorted into `To`/`Cc`/`Bcc` correctly and keep their addresses even when Exchange had tagged one as already-processed (a high-order flag on the recipient type) — the same fix the `.msg` conversion already received.
- Categories and read-receipt requests stored in a `.pst`/`.ost` message now survive conversion as the `Keywords` and `Disposition-Notification-To` headers, matching the `.msg` conversion.
- A delay, relay, or other non-receipt report converted from `.msg`/`.pst`/`.ost` is no longer mislabeled as a read receipt asserting the message was "displayed"; it is exported as a plain report message instead.
- Threading headers (`In-Reply-To`/`References`) in a converted `.pst`/`.ost` message are now angle-bracket-normalized — and free-text tokens that are not real message ids dropped — like `Message-ID`, so reply threading is recognized by mail clients, matching the `.msg` conversion.
- A meeting invitation converted from `.pst`/`.ost` no longer lists a blind-copied (`Bcc`) recipient among the calendar attendees, and a meeting response names the organizer correctly even when Exchange had tagged the organizer's recipient row as already-processed (a high-order flag on the recipient type) — the same fixes the `.msg` conversion already received.
- A bounce or delivery report converted from `.pst`/`.ost` now names the recipient that actually failed even when that recipient row carries the Exchange already-processed flag, instead of falling back to a different (e.g. `Cc`) address.
- A calendar invitation with no resolvable organizer address (from any store) is now exported as a published event instead of an invalid meeting request/response that listed attendees but no organizer, which strict calendar clients refuse to render.
- When a `.pst`/`.ost` message has no structured recipient list, a bare email address recovered from its display-string fallback is now placed as the address rather than the display name, keeping the recipient usable — matching the `.msg` conversion.
- Recovering an HTML body from HTML-encapsulated RTF (`.msg`/`.pst`/`.ost`) now respects RTF group nesting: a formatting-suppression toggle set inside a group no longer suppresses text after the group closes, and a Unicode escape inside an HTML-tag fragment consumes the document's declared number of fallback characters instead of always one.
- An assigned-task request, or its accept/decline response, converted from `.msg`/`.pst`/`.ost` is now a valid calendar object that names the assigner (organizer) and assignee (attendee), instead of a task request/reply with no participants that strict calendar clients refuse to import; when those parties cannot be identified it is exported as a plain published task.
- A converted calendar invitation now declares the same scheduling method in its attachment's `Content-Type` header as in the calendar body — so when an invite is downgraded to a published event (for lacking an organizer or start time) the part is no longer mislabeled as a request/reply over a published-event body.
- A bounce or delivery report converted from `.msg`/`.pst`/`.ost` no longer reports a fabricated `Status` code mined from a server version or build number in its text (for example an Exchange `…15.2.1544.5` banner that surfaced as `5.2.154`); a genuine enhanced-status code in the text is still used.
- A read receipt converted from `.msg`/`.pst`/`.ost` now names the person who read the message (the receipt's own sender) as the notification's recipient, instead of an `unknown` placeholder or the original sender who requested the receipt.
- A `.pst`/`.ost` message that lists some recipients in its structured recipient table but others only in its display fields no longer drops those display-only `Cc`/`Bcc` recipients: the display-field fallback now fills in each missing recipient type instead of only when the whole table is empty — matching the `.msg` conversion.
- Rewriting an HTML body's charset declaration to UTF-8 no longer swallows the `;` that separates it from a following parameter in a multi-parameter `Content-Type` (e.g. `…; charset=…; format=flowed`), which had merged the two parameters together.
- Converting an Outlook item that has only an RTF body (the RTF→plain-text fallback for `.msg`/`.pst`/`.ost`) now respects RTF group nesting for the Unicode fallback-character count, so a count set inside a group no longer leaks past it and drops or leaks a character — the same guarantee the HTML-from-RTF path already received.

### Fixed (conversion robustness)

- Converting a malformed or hostile `.msg` that declares an extreme number of attachments — or attachments far larger than available memory — no longer risks exhausting memory and aborting: the converter now caps the aggregate attachment size and count it will buffer (shared across nested messages), logging and truncating the excess, exactly as the `.pst`/`.ost` conversion already did.
- A corrupt `.pst`/`.ost` whose internal index pages declare an impossible entry size, or whose page references form a cycle, is now reported as a clean conversion error instead of being walked into.
- A corrupt compressed RTF body that references dictionary data ahead of what it has produced (a forward reference no valid Outlook RTF emits) is now detected and decoding stops, instead of emitting stale bytes as garbled body text; a folder that lists itself as its own sub-folder is skipped.
- Recovering deleted/orphaned messages from a large `.pst`/`.ost` now reports ongoing progress instead of appearing to hang, and chooses non-colliding output file names faster.
- An Outlook 2013+ `.ost`/`.pst` no longer risks silently replacing an uncompressed internal block's contents with garbage: whether a stored block is compressed is now read from the correct field, so a block whose raw bytes merely happen to resemble compressed data is left intact.

## 1.2.1 - 2026-06-16

### Fixed (SMTP sending)

- The Send dialog now accepts envelope addresses written with a display name (`Alice <alice@example.com>`) and comma-separated recipient lists (quoted commas such as `"Doe, John" <john@example.com>` included), instead of rejecting them as unsafe — it sends the bare address each server expects.
- A `Bcc:` header in the EML being sent is removed before the message goes out, so the blind recipients are never disclosed to the other recipients; the dialog warns which `Bcc:` addresses it stripped (they are not silently added as recipients).
- "Delivered to N recipients" now counts only the recipients the server actually accepted, not the ones it rejected.
- Accounts with a non-ASCII username or password can now authenticate with `SCRAM-SHA-1` / `SCRAM-SHA-256`: the credentials are SASLprep-normalized before the response is computed, as the mechanism requires.
- Lower-level send correctness: a stray carriage return in the message body is normalized to CRLF, the `SIZE` advertised to the server matches the actual on-the-wire byte count, an out-of-range SMTP reply code is reported as a protocol error, and a malformed SASL challenge is cancelled cleanly (with `*`) instead of being left mid-handshake.

### Fixed (conversion correctness)

- Cancelling a `.pst`/`.ost` conversion now stops it immediately, instead of running to the end while logging every remaining message as a spurious failure.
- Bounce messages and delivery/read receipts converted from `.msg`, `.pst` and `.ost` now produce a standards-compliant `message/delivery-status`: the required Reporting-MTA / Final-Recipient / Action / Status fields are always present, `Status` carries the proper machine-readable code (with the human-readable explanation moved to `Diagnostic-Code`), and `Final-Recipient` names the address that actually failed rather than the bounce's own recipient.
- A task converted to a `task.ics` VTODO with a date-only start or due date is now exported as a calendar DATE rather than midnight UTC, which previously displayed the task a day early for anyone east of Greenwich.
- Meeting responses converted from `.msg` name the meeting organizer correctly even when the response was also copied to a delegate; a `Bcc` recipient no longer appears in a converted meeting's attendee list; calendar text (locations, summaries) with embedded line breaks is escaped correctly; RTF-only bodies no longer leak raw binary picture data into the plain-text fallback; and `In-Reply-To`/`References` threading headers are angle-bracket-normalized like `Message-ID`.

### Added (MSG conversion fidelity)

- S/MIME messages converted from `.msg` keep their cryptographic envelope verifiable: clear-signed messages export as real `multipart/signed` EMLs with the original signature part intact, and opaque signed/encrypted ones as `application/pkcs7-mime` (`smime.p7m`) messages — previously the envelope was buried as a nameless opaque attachment that no mail client could verify or decrypt.
- Calendar items and meeting requests in `.msg` files now export an attached `invite.ics` carrying the real start/end times, location, organizer and attendees; recurring series keep their recurrence rule, time zone and all-day flag — the same fidelity the PST/OST conversion already had.
- Contacts convert with a vCard (`contact.vcf`; names, organization, phones, email addresses) and tasks with a VTODO calendar (`task.ics`; start/due dates and completion state) — parity with the PST/OST conversion.
- Converted MSGs keep more Outlook metadata: conversation threading (`Thread-Topic`/`Thread-Index`), `Importance`/`X-Priority`, `Sensitivity`, `Reply-To`, categories (exported as the standard `Keywords` header) and read-receipt requests (`Disposition-Notification-To`). Attachments keep their `Content-Location`.
- "Sent on behalf of" messages keep both identities: the author now appears in `From:` and the actual transmitter in `Sender:` — previously the author was dropped entirely, and one fallback could even attribute the transmitter's display name to the author's address.

### Fixed (MSG conversion accuracy)

- OLE-embedded objects (spreadsheets or documents pasted as objects) are no longer destroyed: they used to be misrouted into the embedded-message path and replaced with an "Error converting nested message" stub; they now export as `.ole` attachments carrying the object's storage.
- A message with no resolvable sender at all (unsent drafts, keyword-only notes) now emits the mandatory `From:` header with the explicit `undisclosed@invalid` placeholder — flagged in `X-MailKit-Synthesized-Headers` — instead of producing an EML that strict parsers reject.
- Attachments carrying a Content-ID that no HTML body references are no longer hidden as invisible inline parts of `multipart/related`; they stay visible regular attachments (this applies to PST/OST conversion too). A Content-ID lacking its domain half is completed with a synthetic one, with the HTML's `cid:` references rewritten in step, so it parses as a valid msg-id.
- An HTML body decoded from a legacy code page no longer keeps a stale `<meta charset=...>` declaration contradicting the UTF-8 re-encoding (the same fix the PST pipeline received).
- An embedded message whose content carries a line longer than the RFC 5322 limit is now declared `Content-Transfer-Encoding: binary` instead of non-conformant `8bit`, and overlong attachment `Content-Location` values are folded within the line-length limit.

### Added (PST/OST non-mail items, recurrence and integrity)

- Recurring appointments and meetings now export their full series: the calendar invite carries the recurrence rule (daily/weekly/monthly/nth-weekday/yearly, with end-by-date or end-after-count), deleted occurrences as exception dates, and the event's own time zone — so a "every Tuesday at 10:00" meeting stays at 10:00 local time across DST changes instead of drifting by an hour. All-day events are exported as true all-day calendar entries.
- Contacts, tasks, sticky notes, journal entries and distribution lists can now be converted too (opt-in checkbox in the conversion dialog): contacts become EMLs carrying a vCard (`contact.vcf`) with names, organization, phones and email addresses; tasks carry a VTODO calendar (`task.ics`) with due date and completion state; distribution lists export with their resolved member list as the body.
- When an archive contains item types the conversion skips, the console now reports a per-class summary (e.g. `IPM.Contact x6`) instead of leaving the omission to be discovered later.
- OLE-embedded objects (spreadsheets, documents embedded as objects in old messages) are no longer dropped: their raw storage bytes are exported as an `.ole` attachment.
- A new "Verify on-disk checksums while reading" option validates every page and block checksum of the archive while converting, turning silent bit rot into clearly reported corruption errors; compressed RTF bodies always verify their checksum and report mismatches.
- A message that claims attachments which cannot be read from a damaged attachment table is now reported and counted as a failure instead of exporting "successfully" without them.

### Added (PST/OST message-class coverage)

- Delivery-status notifications and read receipts (non-delivery / delivery reports and read / non-read receipts) now convert into a proper `multipart/report` message — the machine-readable delivery-status / disposition-notification survives instead of being flattened into a plain text body, the same way the MSG conversion already handles them.
- S/MIME messages converted from `.pst`/`.ost` keep their cryptographic envelope verifiable: clear-signed messages export as real `multipart/signed` EMLs with the original signature intact, and opaque signed/encrypted ones as `application/pkcs7-mime` (`smime.p7m`) — previously the converter re-encoded the structure and silently broke the signature.
- Meeting responses now record the attendee's answer: an accept / decline / tentative reply exports an `invite.ics` carrying the matching participation status and the correct organizer/attendee roles, instead of a generic invite.
- Task-assignment requests and their accept / decline / update responses now carry the correct calendar method (request / reply) instead of being mislabeled as a plain published task.
- More Outlook item types now convert instead of being silently skipped: documents, recall reports and recall requests, remote-mail headers, resend items, item-status reports and recurrence-exception items are exported as plain messages (with a console note that no specialized handler applied), so the PST/OST conversion drops nothing the MSG conversion would keep.

### Fixed (PST/OST conversion accuracy)

- Messages with large recipient or attachment tables (roughly 40+ recipients or 50+ attachments) no longer lose them: the tables' row data was being looked up in the wrong part of the archive's storage tree, silently dropping every recipient and attachment of such messages — mass-distribution mail in particular — and occasionally fabricating a bogus recipient from unrelated data. Both ANSI and Unicode archives are affected and covered.
- A message whose only body is plain text encapsulated in RTF (`\fromtext`, common for messages written by non-Outlook clients) now exports that text as its readable body instead of an empty message with a `body.rtf` attachment.
- Uncompressed blocks in 2013-format OST files whose content coincidentally looks zlib-compressed can no longer be "decompressed" into garbage that replaces the real content.
- Synthesized headers whose value is one unbreakable token longer than the RFC 5322 line limit (e.g. the `Thread-Index` of a very long conversation) are now folded so strict parsers accept the converted message.

### Added (PST/OST conversion fidelity)

- Converted messages keep more of their Outlook metadata: `Sensitivity` (Personal/Private/Company-Confidential), the conversation-threading `Thread-Topic` and `Thread-Index` headers, and `Reply-To` (resolved from the archive's reply-recipient entries, honouring the configured address preference) are now exported. Attachments keep their `Content-Location`, so converted MHTML web archives stay browsable.

### Fixed (PST/OST conversion quality review)

- A message whose stored properties cannot be read (corrupt or truncated archive) no longer exports as a blank "No Subject" message silently counted as a success: it is reported in the MailKit console and counted as failed — for folder messages, recovered/orphaned messages and embedded messages alike.
- An HTML body stored as a string property no longer keeps a stale `<meta charset=...>` declaration contradicting the converted message's UTF-8 encoding; it is rewritten the same way as byte-stored HTML bodies.
- An attachment larger than the configured cap is now diagnosed as such — naming the "Max single attachment size (MB)" dialog option that raises it — instead of being misreported as having no stored content.
- UTF-32, EBCDIC (US-Canada and the national variants), Korean Wansung, Traditional-Chinese ISO-2022 and EUC-TW code pages now decode with their actual character sets instead of degrading to windows-1252.
- RTF that merely encapsulates the plain-text body (`\fromtext`) is no longer exported as a redundant `body.rtf` attachment; if such RTF is the only body content the message has, it is still kept so nothing is lost.
- A nested message replaced by the depth-cap placeholder now counts as a failed attachment in the conversion summary instead of passing unnoticed.
- Quoted-printable bodies can no longer emit a line one character over the RFC 2045 limit when a full line ends in a space or tab.
- Simplified-Chinese (GB2312/EUC-CN), ISO-2022-CN, extended EUC-JP, Arabic ASMO-708, logical Hebrew (ISO-8859-8-I), ISCII Devanagari and ten more Macintosh code pages now convert with their actual character set instead of degrading to garbled windows-1252 text. When a store uses a code page the JDK genuinely cannot decode, the degradation is now reported in the log instead of being silent.
- A message that names no code page of its own now picks up the store-wide default code page (when the archive records one) before assuming windows-1252.
- An embedded message that cannot be resolved from a damaged archive is now reported in the MailKit console and counted in the conversion summary instead of vanishing silently; the same accountability applies to attachments whose stored content is missing. Attachments that are by-reference links to files outside the archive are noted as such.
- Exported embedded messages are now named after the attachment's display name (typically the embedded message's subject, e.g. `First email.eml`) instead of the generic `attachment.dat.eml`.
- Exchange journal reports stay identifiable after conversion: the `X-MS-Journal-Report` marker now survives even when "Use original SMTP headers" is disabled.
- Converting an S/MIME message now notes in the MailKit console that the re-encoded EML cannot keep the original signature/encryption envelope verifiable.
- HTML extracted from RTF-encapsulated bodies is more faithful: character escapes inside `htmltag` markup runs are decoded with the right code page, escaped braces no longer truncate tags, and Unicode escapes written in unsigned form (as some writers do) are no longer dropped.
- A compressed RTF body no longer loses its final one or two characters when the compressed stream ends exactly on a flag byte, and a truncated RTF stream is now reported as a warning rather than silently shortened.
- Original transport-header lines longer than the RFC 5322 hard limit of 998 characters are now re-folded at whitespace so strict parsers accept the converted message.

### Changed

- PST/OST conversion no longer wraps everything in a synthetic `Folder_290` directory: the archive's real top-level folders now land directly in the chosen target directory.
- A genuine RTF message body is now preserved as a `body.rtf` attachment (with its original bytes) instead of being emitted as a `text/rtf` body alternative that no mail client can render — in MSG conversion as well as PST. An MSG whose RTF is just the encapsulated copy of its HTML body no longer carries that redundant copy at all, which substantially shrinks typical converted messages.
- Converting a `.msg` file now asks before overwriting an existing `.eml` of the same name instead of silently replacing it.
- Canceling an MSG conversion now takes effect while the message is being converted, not only after it finishes.

### Fixed

- Messages whose body is stored only as RTF-encapsulated HTML no longer leak RTF header text (font names like "Arial;", "Microsoft Exchange Server;") into the converted HTML body.
- Calendar invites exported from PST/OST archives are now valid iCalendar scheduling messages: plain appointments are published (`METHOD:PUBLISH`) instead of posing as meeting requests, meeting requests/cancellations/responses carry their attendee list and the matching method, and an appointment with no stored start time no longer gets an invite fabricated at conversion time.
- Calendar invite text with emoji or other non-Latin characters no longer risks corruption at line-fold points, and a quote or line break in an organizer/attendee name or address can no longer break or inject invite content.
- Cyrillic, Greek, Hebrew, Turkish, Baltic, Thai, KOI8, DOS-codepage and Mac-codepage PST messages now convert with their actual character set instead of degrading to garbled windows-1252 text; Japanese/Korean/Chinese text now uses the exact Windows codepage variants Outlook wrote (windows-31j, windows-949, windows-950). Folder names and String8 properties now honour the message codepage separately from the HTML body's internet codepage.
- The spam-confidence header (`X-MS-Exchange-Organization-SCL`) is now written whenever the archive stores one; it used to vanish unless a Message-ID happened to be synthesized at the same time.
- Exported messages keep their conversation threading: `In-Reply-To` and `References` are written from the archive when the original transport headers are missing, along with `Importance`/`X-Priority` and, for on-behalf-of messages, the correct `From:` (author) plus `Sender:` (actual sender) pair.
- A message with an empty subject no longer gains a fabricated "No Subject" header in the converted EML (the filename fallback is unchanged).
- Synthesized addresses for Exchange-only correspondents now end in the reserved `.invalid` domain instead of `@example.com`, so they are recognizable and can never route.
- Plain-text bodies with classic-Mac (CR-only) line endings no longer have their lines joined together, and leading/trailing whitespace in message bodies survives conversion instead of being trimmed away.
- Attachments marked hidden in Outlook (typical for inline cid-referenced images) are now correctly grouped with the message body as inline parts.
- A stored Message-ID without angle brackets is now wrapped in them as RFC 5322 requires.
- MSG conversion now resolves the sender's real SMTP address when the message stores it beside an Exchange X.500 DN, and exports the stored spam-confidence level (`X-MS-Exchange-Organization-SCL`); both were previously dropped because the property lookup could never match.
- Exchange X.500 addresses whose CN segment contains `@` (e.g. `/O=ORG/.../CN=USER@HOST`) no longer leak raw — spaces, slashes and all — into From/To headers of converted messages; they are encapsulated like other Exchange-only addresses, so the headers stay parseable.
- A corrupt RTF body stream no longer fails the whole MSG conversion: the message converts with its remaining plain-text/HTML bodies and the problem is reported in the MailKit console.
- An attachment whose stored MIME type is `multipart/...` (e.g. a forwarded S/MIME blob) no longer produces a structurally invalid part in the converted EML; its payload is kept under `application/octet-stream`.
- An MSG without a structured recipient table no longer emits unparseable `"John Doe" <John Doe>` addresses built from bare display names; names get the explicit `undisclosed@invalid` placeholder instead.
- HTML recovered from RTF-encapsulated MSG bodies is more faithful: character escapes inside markup runs are decoded, escaped braces no longer truncate tags, and the character following a Unicode escape is no longer duplicated.
- Messages with inline images now declare the `type` parameter RFC 2387 requires on `multipart/related`, satisfying strict MIME validators.
- Two embedded messages with the same subject now get distinct nested `.eml` attachment names instead of identical ones.
- An MSG with more than 2048 recipients now converts with the first 2048 (and a console warning) instead of failing outright.

## 1.2.0 - 2026-06-10

### Added

- Outlook PST and OST archive files are now recognized with distinct icons in the Project view.
- Added a new action to convert Outlook PST and OST archives into a directory tree of standard EML files, complete with configurable duplicate handling, optional message-count limits, and SMTP header extraction.
- The PST converter now seamlessly supports "Highly Encrypted" (Enigma cipher) and "Password Protected" archive files.
- Recipient and sender addresses are now preserved for Exchange-only correspondents: when a message carries no cached SMTP address, the converter keeps the Exchange address (legacyExchangeDN) instead of leaving the field blank.
- Multi-tab support for conversion logging in the MailKit tool window, displaying separate real-time logs for MSG and PST/OST conversions. Added detailed logging for discovered attachments, embedded messages, and reasons for skipped messages.
- Support for extracting `legacyExchangeDN` addresses (e.g. `/O=EXCHANGELABS/OU=EXCHANGE ADMINISTRATIVE GROUP...`) from PST/OST archives and rendering them into EML headers.
- PST/OST conversion can now recover messages the normal folder walk misses: soft-deleted items still attached to a folder are written into a `Recovered Items` folder, and fully detached (orphaned) message nodes into an `Orphaned Items` folder. Both are enabled by default and can be turned off in the conversion dialog.
- PST/OST conversion now exports calendar items: appointments and meeting requests are written as EML with an attached calendar invite (`invite.ics`) carrying the start/end time and location, instead of being silently skipped.
- The PST conversion dialog now displays a list of supported and ignored message classes, and includes a link to open a GitHub issue to request support for new ones.
- Added a "Send EML..." button directly to the editor toolbar for quick access. This button can be toggled off via the "Show 'Send EML...' button in editor toolbar" setting under **Tools > MailKit > SMTP**.
- When you change the host, port, or envelope From/To in the Send dialog, a checkbox now offers to save those values back to the selected profile — useful when pointing an existing profile at a new environment. The checkbox appears only while the values differ from the profile and is off by default, so a one-off override never touches the profile.
- "Send EML…" now accepts a multi-file selection: pick several `.eml` files in the Project view and send them from one dialog. The envelope is entered once and shared by every message, an "On failure" option chooses between continuing with the remaining messages or stopping at the first failure, and the dialog stays open showing live per-file progress with the ability to cancel the remaining sends. Each message still gets its own console transcript and audit-log entry.

### Changed

- All MailKit context-menu actions (Convert to EML, Send EML, Save Attachment) now feature the standard MailKit EML icon for better visibility and consistency.

### Fixed

- Legacy ANSI (non-Unicode) Outlook `.msg` files now convert with the message's actual codepage: Cyrillic, Chinese, and other non-Western subjects and bodies previously came out as garbled windows-1252 text.
- Replies converted from `.msg` files that carry no stored internet headers now keep their `In-Reply-To`/`References` headers, so mail clients can reconstruct the conversation thread in the exported EML.
- A crafted attachment filename containing line breaks can no longer inject arbitrary headers into the EML generated by MSG or PST conversion.
- Converting a `.msg` file no longer loads the whole file into memory — it is read from disk block-by-block on demand, so very large messages with big attachments convert without memory spikes.
- MSG attachments whose payload is missing or unreadable are now reported in the MailKit console instead of silently becoming zero-byte files, and the console now confirms successful MSG conversions.
- EML files generated from messages that carried their own `MIME-Version` header no longer come out without one: the original header was dropped during conversion but never re-added, leaving the multipart output formally invalid.
- PST/OST folders holding more than ~80 messages no longer risk losing or garbling the messages past that point during conversion: the converter mis-addressed the folder's internal message table once it grew beyond one storage block.
- Embedded message attachments (an email attached inside another email) are now actually extracted into nested `.eml` attachments during PST/OST conversion; previously they were silently skipped because the embedded item was looked up by the wrong identifier.
- Opening a corrupted PST/OST archive whose encryption marker is damaged now fails immediately with a clear error instead of exporting unreadable garbage messages.
- Converting a very large PST/OST archive no longer loads the archive's entire internal index into memory up front — conversion starts faster and uses far less memory on multi-gigabyte archives, and large attachments can be read as a stream.
- ANSI-format PST archives (Outlook 97–2002) now convert correctly: message subjects, bodies, senders, and recipients were previously read as empty (or failed outright) because the converter mis-read the archive's internal block layout, so no messages were exported.
- Posted notes (`IPM.Post`, items posted directly into a folder) are now exported during PST/OST conversion instead of being skipped as an unsupported message class.
- Sending via SMTP now reports a failed TLS handshake (untrusted certificate, hostname mismatch) as a TLS error instead of a generic I/O error, so the failure reason is clear in the send result.
- OAuth-token sign-in (XOAUTH2 / OAUTHBEARER) is no longer attempted over an unencrypted connection unless plaintext authentication is explicitly allowed — bearer tokens now get the same protection as passwords.
- Sends to servers that deliver per-recipient verdicts (PRDR, e.g. Exim) now read the verdicts correctly; previously the per-recipient results could be misattributed and the session desynchronized.
- "Use MX routing" now tries every MX host of the sender domain in preference order instead of only the first one, refuses domains that declare "no mail accepted" (null MX), and reports the failed hosts in the send transcript.
- "Authentication optional" now matches its description: a rejected sign-in no longer aborts the send, while the strict variant still treats a rejection as fatal.
- Client certificates with EC (and Ed25519) keys can now be used for mutual TLS; unsupported key formats (encrypted or PKCS#1 PEM) are reported with an actionable message instead of a cryptic parse error.
- Sending to very old servers that reject `EHLO` now falls back to `HELO` automatically, and legacy `AUTH=` capability advertisements are recognized.
- When optional STARTTLS is rejected by the server, the cleartext downgrade is now noted in the send transcript.
- Large messages sent with CHUNKING (BDAT) are now streamed in chunks instead of being buffered fully in memory.
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
