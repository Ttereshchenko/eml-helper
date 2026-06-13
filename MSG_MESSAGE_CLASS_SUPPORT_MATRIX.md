# MSG → EML Message-Class Support Matrix

Coverage of the MSG→EML conversion path against Microsoft's Outlook
[*Item Types and Message Classes*](https://learn.microsoft.com/en-us/office/vba/outlook/concepts/forms/item-types-and-message-classes)
taxonomy. Columns 1–2 are reproduced verbatim from that page (`ms.date` 2019-06-08).
Column 3 reflects the converter in `conversion/msg/`; column 4 points at the
exercising fixture under `src/test/resources/samples/msg/`.

**"Is supported"** = the converter has dedicated logic that reconstructs the
class's specialized payload (calendar invite, vCard, VTODO, member listing,
`multipart/report`, hoisted S/MIME envelope) — or the class is plain mail.
**No** = the item still converts, but only as a *generic* message via fallback
(content preserved, specialized semantics not reconstructed), and it now emits a
`No specialized handler for message class …` downgrade log.

| Message class ID | Message class is used to identify a form for: | Is supported | Sample (`src/test/resources/samples/msg/`) |
| --- | --- | :---: | --- |
| IPM.Activity | Journal entries | No | — |
| IPM.Appointment | Appointments | Yes | `msgClassAppointment.msg` |
| IPM.Contact | Contacts | Yes | `msgClassContact.msg` |
| IPM.DistList | Distribution lists | Yes | `msgreader_distribution_list.msg` &sup2; |
| IPM.Document | Documents | No | — |
| IPM.OLE.Class | Exception item of a recurrence series | No | — |
| IPM | Items for which the specified form cannot be found | Yes &sup1; | — &sup3; |
| IPM.Note | Email messages | Yes | `simple_test_msg.msg` &sup3; |
| IPM.Note.IMC.Notification | Reports from the Internet Mail Connect (the Exchange Server gateway to the Internet) | No | — |
| IPM.Note.Rules.OofTemplate.Microsoft | Out-of-office templates | No | — |
| IPM.Post | Posting notes in a folder | Yes | `msgClassPost.msg` |
| IPM.StickyNote | Creating notes | No &#8308; | `msgClassStickyNote.msg` |
| IPM.Recall.Report | Message recall reports | No | — |
| IPM.Outlook.Recall | Recalling sent messages from recipient Inboxes | No | — |
| IPM.Remote | Remote Mail message headers | No | — |
| IPM.Note.Rules.ReplyTemplate.Microsoft | Editing rule reply templates | No | — |
| IPM.Report | Reporting item status | No &#8309; | — |
| IPM.Resend | Resending a failed message | No | — |
| IPM.Schedule.Meeting.Canceled | Meeting cancellations | Yes | — &#8310; |
| IPM.Schedule.Meeting.Request | Meeting requests | Yes | `aspose_meeting_recurring.msg` |
| IPM.Schedule.Meeting.Resp.Neg | Responses to decline meeting requests | Yes | — &#8310; |
| IPM.Schedule.Meeting.Resp.Pos | Responses to accept meeting requests | Yes | — &#8310; |
| IPM.Schedule.Meeting.Resp.Tent | Responses to tentatively accept meeting requests | Yes | — &#8310; |
| IPM.Note.Secure | Encrypted notes to other people | Yes | `bbottema_smime_encrypted.msg` &#8311; |
| IPM.Note.Secure.Sign | Digitally signed notes to other people | Yes | `bbottema_smime_signed.msg` &#8311; |
| IPM.Task | Tasks | Yes | `msgClassTask.msg` |
| IPM.TaskRequest.Accept | Responses to accept task requests | Yes | — &#8310; |
| IPM.TaskRequest.Decline | Responses to decline task requests | Yes | — &#8310; |
| IPM.TaskRequest | Task requests | Yes | — &#8310; |
| IPM.TaskRequest.Update | Updates to requested tasks | Yes | — &#8310; |

### Footnotes

1. **IPM** (no form found) is handled as a generic email — best-effort fidelity
   per `[MS-OXCMSG] §2.2.1.3`, which defines a missing/unknown class as the
   generic note.
2. **IPM.DistList** member decoding (One-Off EntryIDs, `[MS-OXCDATA] §2.2.5.1`).
   A real fixture is now vendored — `msgreader_distribution_list.msg` (MIT, from
   `github.com/Sicos1977/MSGReader`; 3 one-off members incl. a Unicode/IDN
   address) — and exercised end-to-end by `MsgSampleCorpusTest`. Vendoring it
   **exposed a real defect**: the one-off provider MUID had been endianness-swapped
   (`A4 1F 2B 81 …`), so the decoder returned zero members from every real Outlook
   distribution list; real files store it verbatim as `81 2B 1F A4 …`. Fixed; the
   hand-built `DistributionListMembersTest` blobs had encoded the same wrong order
   and were corrected too.
3. Many **IPM.Note** fixtures exist — e.g. `example_received_regular.msg`,
   `example_sent_regular.msg`, `cyrillic_message.msg`, `HTMLBodyBinary_UTF-8.msg`,
   `lots-of-recipients.msg`; `simple_test_msg.msg` is a representative.
4. **IPM.StickyNote** converts as a generic message — the note text is preserved
   but no note-specific artifact is produced; it triggers the downgrade log. (A
   `msgClassStickyNote.msg` fixture exists and exercises the fallback path.)
5. This **IPM.Report** row is the legacy *item-status* form and is not specially
   handled. It is **distinct from** the MAPI `REPORT.*` delivery/read-receipt
   classes (`REPORT.…​.NDR` / `.DR` / `.IPNRN` / `.IPNNRN`), which **are**
   supported and reconstructed as an RFC 6522 `multipart/report` — those classes
   are not part of this VBA table (they come from `[MS-OXCMAIL] §2.5`). See below.
6. Supported in code (iTIP `CANCEL`/`REQUEST`/`REPLY` with differentiated
   `PARTSTAT`, and task-request `METHOD`), but **no binary `.msg` fixture** — same
   POI named-property write limitation as footnote 2; the logic is unit-tested at
   the generator/seam level (`ICalendarGeneratorTest`, `MsgToEmlConverterTest`).
7. The repo sample carries the modern S/MIME class
   (`IPM.Note.SMIME` / `IPM.Note.SMIME.MultipartSigned`), handled by the same
   top-level-entity hoist (`[MS-OXOSMIME] §2.2.1`) that **now also matches**
   `IPM.Note.Secure*`. `logsat.com_signatures_valid.msg` and
   `bbottema_smime_signed_encrypted.msg` also exercise the signed/encrypted path.

### Not in the VBA table but supported: delivery/read reports

| MAPI class | Meaning | Is supported | Sample |
| --- | --- | :---: | --- |
| REPORT.*.NDR | Non-delivery report (DSN) | Yes | — &sup2;&#8310; |
| REPORT.*.DR | Delivery report | Yes | — &sup2;&#8310; |
| REPORT.*.IPNRN | Read receipt | Yes | — &sup2;&#8310; |
| REPORT.*.IPNNRN | Non-read (deleted-unread) receipt | Yes | — &sup2;&#8310; |

Reconstructed as RFC 6522 `multipart/report` (`message/delivery-status`,
RFC 3464; `message/disposition-notification`, RFC 8098) by `ReportGenerator`.
Fully unit-tested (`ReportGeneratorTest`) and exercised through the converter
with `MsgFixtureBuilder` (`PidTagReportText`), so no vendored `.msg` fixture is
strictly required for these.
