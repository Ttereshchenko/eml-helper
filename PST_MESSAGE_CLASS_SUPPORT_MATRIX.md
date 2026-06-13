# PST/OST → EML Message-Class Support Matrix

Coverage of the PST/OST→EML conversion path (`conversion/pst/PstToEmlConverter.java`
+ the `pst-parser` module) against Microsoft's Outlook
[*Item Types and Message Classes*](https://learn.microsoft.com/en-us/office/vba/outlook/concepts/forms/item-types-and-message-classes)
taxonomy. Columns 1–2 are reproduced verbatim from that page (`ms.date` 2019-06-08).
Column 3 reflects the converter; column 4 points at the exercising fixture under
`src/test/resources/samples/pst/` (or the covering test when no public PST fixture
exists — see footnotes).

**"Is supported"** = the converter has dedicated logic that reconstructs the class's
specialized payload (calendar invite, vCard, VTODO, member listing,
`multipart/report`, hoisted S/MIME envelope) — or the class is plain mail.
**No** = the item still **converts**, but only as a *generic* message via fallback
(content preserved, specialized semantics not reconstructed); it emits a
`No specialized handler for message class …` downgrade log.

> **Allow-list (read first — `PstToEmlConverter.isAllowedMessageClass`).** Unlike the
> MSG path, PST is a *bulk folder walk* and gates items through an allow-list. Every
> class **in this table now converts** &sup1;, but in two tiers: most are exported
> unconditionally; the five *non-mail* classes (`IPM.Contact`, `IPM.Task`,
> `IPM.StickyNote`, `IPM.DistList`, `IPM.Activity`, and so `IPM.TaskRequest*`) are
> exported **only** when the **"Convert contacts, tasks, notes and distribution lists"**
> option is enabled &sup2;. Out-of-taxonomy internal items not on the list (e.g.
> `IPM.Microsoft.ScheduleData.FreeBusy`) are still skipped, and every skip is logged.

| Message class ID | Message class is used to identify a form for: | Is supported | Sample (`src/test/resources/samples/pst/`) |
| --- | --- | :---: | --- |
| IPM.Activity | Journal entries | No &sup2;&#8309; | — |
| IPM.Appointment | Appointments | Yes | `dist-list.pst` &sup3;, `aspose-outlook.pst` |
| IPM.Contact | Contacts | Yes &sup2; | `aspose-contacts.pst`, `dist-list.pst` |
| IPM.DistList | Distribution lists | Yes &sup2; | `dist-list.pst` |
| IPM.Document | Documents | No &sup1; | — |
| IPM.OLE.Class | Exception item of a recurrence series | No &sup1; | — |
| IPM | Items for which the specified form cannot be found | No &sup1; | — |
| IPM.Note | Email messages | Yes | `tika-testPST.pst` &#8310; |
| IPM.Note.IMC.Notification | Reports from the Internet Mail Connect (the Exchange Server gateway to the Internet) | No | — |
| IPM.Note.Rules.OofTemplate.Microsoft | Out-of-office templates | No | — |
| IPM.Post | Posting notes in a folder | Yes | `ansi-test.pst` |
| IPM.StickyNote | Creating notes | No &sup2;&#8309; | — |
| IPM.Recall.Report | Message recall reports | No &sup1; | — |
| IPM.Outlook.Recall | Recalling sent messages from recipient Inboxes | No &sup1; | — |
| IPM.Remote | Remote Mail message headers | No &sup1; | — |
| IPM.Note.Rules.ReplyTemplate.Microsoft | Editing rule reply templates | No | — |
| IPM.Report | Reporting item status | No &sup1;&#8308; | — |
| IPM.Resend | Resending a failed message | No &sup1; | — |
| IPM.Schedule.Meeting.Canceled | Meeting cancellations | Yes | — &#8311; |
| IPM.Schedule.Meeting.Request | Meeting requests | Yes | — &#8311; |
| IPM.Schedule.Meeting.Resp.Neg | Responses to decline meeting requests | Yes &#8312; | — &#8311; |
| IPM.Schedule.Meeting.Resp.Pos | Responses to accept meeting requests | Yes &#8312; | — &#8311; |
| IPM.Schedule.Meeting.Resp.Tent | Responses to tentatively accept meeting requests | Yes &#8312; | — &#8311; |
| IPM.Note.Secure | Encrypted notes to other people | Yes &#8313; | — &#8311; |
| IPM.Note.Secure.Sign | Digitally signed notes to other people | Yes &#8313; | — &#8311; |
| IPM.Task | Tasks | Yes &sup2; | — &#8311; |
| IPM.TaskRequest.Accept | Responses to accept task requests | Yes &sup2;&#8310; | — &#8311; |
| IPM.TaskRequest.Decline | Responses to decline task requests | Yes &sup2;&#8310; | — &#8311; |
| IPM.TaskRequest | Task requests | Yes &sup2;&#8310; | — &#8311; |
| IPM.TaskRequest.Update | Updates to requested tasks | Yes &sup2;&#8310; | — &#8311; |

### Footnotes

1. **Allow-list expansion (2026-06-13, owner decision).** These message-like classes
   were previously *dropped* by the allow-list; they are now exported as a generic EML
   (content preserved, downgrade-logged) for parity with the MSG path, which has no
   allow-list. The literal `IPM` ("no form found") is admitted as an exact match per
   `[MS-OXCMSG] §2.2.1.3`. Gate logic in `isAllowedMessageClass` /
   `ALLOWED_MESSAGE_CLASSES`.
2. **Non-mail opt-in.** Exported only when `Options.exportNonMailItems` is on
   (`NON_MAIL_MESSAGE_CLASSES`). `IPM.TaskRequest*` matches the `IPM.Task` prefix, so it
   too is gated behind this option (the owner chose to keep it opt-in rather than promote
   it to always-mail).
3. `dist-list.pst` carries a **real recurring** appointment (weekly Tuesday, Pacific
   time, one deleted occurrence); the invite reproduces the full series (RRULE + EXDATE +
   VTIMEZONE).
4. This **IPM.Report** row is the legacy *item-status* form. It is **distinct from** the
   MAPI `REPORT.*` delivery/read-receipt classes (`…​.NDR` / `.DR` / `.IPNRN` /
   `.IPNNRN`), which **are** specially reconstructed — see the table below.
5. `IPM.StickyNote` and `IPM.Activity` (journal) export their text as a generic EML when
   the non-mail option is on; no note/journal-specific artifact is produced.
6. Many **IPM.Note** fixtures exist — `tika-testPST.pst`, `testPST.pst`,
   `testPST_variousBodyTypes.pst`, `aspose-outlook.pst`, `submessage.pst` (embedded
   message), `example-2013.ost` (Outlook-2013 OST). `tika-testPST.pst` is representative.
7. **No license-clean public PST contains this class** (verified by an authoritative
   `PidTagMessageClass` inventory of every vendored store: only `IPM.Note`/`.Post`/
   `.Appointment`/`.Contact`/`.DistList` occur). The dispatch and payload are covered by
   in-memory `Message` doubles in
   `src/test/java/.../conversion/pst/PstToEmlConverterTest.java` (same conclusion the MSG
   pass reached for these transient mailbox-internal classes).
8. Meeting-response `REPLY` carries a differentiated `PARTSTAT`
   (`Resp.Pos`→ACCEPTED, `.Neg`→DECLINED, `.Tent`→TENTATIVE) and swaps the
   organizer/attendee roles per RFC 5546 §3.2.3.
9. `IPM.Note.SMIME*` / `IPM.Note.Secure*` hoist their stored MIME envelope to the top
   level ([MS-OXOSMIME] §2.2.1) via the shared POI-free `conversion/SmimeEntityHoist`, so
   the signature/encryption stays verifiable instead of being re-encoded. Unit-tested in
   `SmimeEntityHoistTest`.
10. `IPM.TaskRequest*` emits the correct iTIP `METHOD` (`REQUEST` / `REPLY`) rather than
    being mislabeled `PUBLISH` by a naive `startsWith("IPM.Task")` — the same gate bug the
    MSG pass fixed.

### Not in the VBA table but supported: delivery/read reports

| MAPI class | Meaning | Is supported | Sample |
| --- | --- | :---: | --- |
| REPORT.*.NDR | Non-delivery report (DSN) | Yes | — &#8311; |
| REPORT.*.DR | Delivery report | Yes | — &#8311; |
| REPORT.*.IPNRN | Read receipt | Yes | — &#8311; |
| REPORT.*.IPNNRN | Non-read (deleted-unread) receipt | Yes | — &#8311; |

`REPORT.*` was already on the allow-list but was previously **flattened to a generic
body**. It is now reconstructed as an RFC 6522 `multipart/report`
(`message/delivery-status`, RFC 3464; `message/disposition-notification`, RFC 8098) by
the shared POI-free `conversion/ReportGenerator` (also used by the MSG path), via
`emitReport` + `EmlSerializer.setRawEntity`. Covered by `PstToEmlConverterTest` over
in-memory doubles; no vendored PST fixture exists for these transient classes.
