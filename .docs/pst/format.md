# PST / OST file-format reference

Internal reference for the `conversion/pst/` parser. **Not auto-loaded** — read it
only when working on PST/OST parsing, and prefer grepping to the relevant row over
reading the whole file.

Authoritative external spec: **[MS-PST] Outlook Personal Folders (.pst) File
Format** (Microsoft Open Specifications), shipped at
[`.docs/pst/[MS-PST].pdf`](./[MS-PST].pdf). OST shares the same on-disk
structures. Section numbers below (`§x.y.z`) are verified against that PDF; the
byte offsets are taken from *this repo's parser* (the source of truth for what
MailKit actually does). When the two disagree, the code wins for behavior
questions — fix the code only against a cited MS-PST passage.

> **Don't re-read the PDF.** This file is the cache: it carries the exact
> `§` + page for every structure the parser touches, so you almost never need to
> open the 7 MB spec. If you do, see "Reading the PDF efficiently" at the bottom —
> jump to one section, never read it whole.

## Format variants

MailKit recognizes three variants via the 16-bit `wVer` field at header offset
`0x0A` (`PstFile.HEADER_VERSION_OFFSET = 10`), dispatched in
`PstFile.Format.fromVersion`. The `wVer` rules are `[MS-PST] §2.2.2.6` (HEADER);
ANSI-vs-Unicode is `§2.2.1.2`.

| Variant | Outlook era | `wVer` | Pointer width | File-size cap | MailKit `Format` |
|---------|-------------|--------|---------------|---------------|------------------|
| **ANSI** | Outlook 97–2002 | `14` or `15` | 32-bit (BID/BREF, file offsets) | **2 GB** (enforced: `PstException` past `2 * 1024³`) | `ANSI` |
| **Unicode** | **Outlook 2003+** | `>= 23` | 64-bit | effectively unbounded | `UNICODE` |
| **Unicode 2013** | **Outlook 2013+** | `>= 36` | 64-bit | effectively unbounded | `UNICODE_2013` |

> **Spec-coverage caveat — read before trusting the 2013 row.** `[MS-PST] §2.2.2.6`
> only defines `wVer` = 14/15 (ANSI) and `>= 23` (Unicode), and notes `37` flags a
> Windows-Information-Protection writer. The spec describes BTPAGE as a **512-byte**
> page (`§2.2.2.7.7.1`) with a **1-byte** `cEnt`. The **4 KB-page "Outlook 2013+"
> variant** MailKit handles (`wVer >= 36`, 4096-byte pages, 2-byte entry count,
> `4056` trailer offset, block compression) is **NOT in [MS-PST]** — it is
> reverse-engineered (cf. libpff). Treat `conversion/pst/` as the authority there;
> there is no spec passage to cite for those constants.

Header magic: `0x4E444221` little-endian = ASCII `"!BDN"`
(`PstFile.MAGIC_NUMBER`), validated at offset 0 — `[MS-PST] §2.2.2.6` (`dwMagic`).
`OstFileType` covers the Outlook offline-store (.ost) sibling (same structures).

## Layout constants by variant

Values the parser branches on. All offsets little-endian. Spec rows cite MS-PST;
the 2013 column is parser-only where marked **(no spec)**.

| Concept | ANSI | Unicode | Unicode 2013 | Spec | Where in code |
|---------|------|---------|--------------|------|---------------|
| Header encryption-type offset (`bCryptMethod`) | `461` | `513` | `513` | §2.2.2.6 | `PstFile` `encryptionOffset` |
| BTree page size | `512` | `512` | **`4096` (no spec)** | §2.2.2.7.7.1 | `NodeDatabase` `pageSize` |
| Page (`rgentries`) trailer offset | `496` | `488` | **`4056` (no spec)** | §2.2.2.7.7.1 | `NodeDatabase` `trailerOffset` |
| BTPAGE entry-count (`cEnt`) width | 1 byte | 1 byte | **2 bytes (no spec)** | §2.2.2.7.7.1 | `NodeDatabase` BTPAGE read |
| Block trailer (`BLOCKTRAILER`) size | 12 bytes | 16 bytes | 16 bytes | §2.2.2.8.1 | `NodeDatabase` block read |
| Max block payload | `8180` (8192−12) | `8176` (8192−16) | `8176` | §2.2.2.8 | `NodeDatabase.maxBlockPayload` (≈) |
| BTENTRY (branch) entry size | 8 | 16 | 16 | §2.2.2.7.7.2 | `NodeDatabase` SIBLOCK/branch |
| BBT/NBT leaf entry size | 12 | 24 | 24 | §2.2.2.7.7.3 / .4 | `NodeDatabase` SLBLOCK/leaf |
| XBLOCK child-entry size | 4 | 8 | 8 | §2.2.2.8.3.2.1 | `NodeDatabase` XBLOCK |
| 2013 compressed-block inflated size | — | — | `getInt(offset+24)` **(no spec)** | — | `NodeDatabase` (`isCompressedBlock`) |

> **Outlook 2013+ specifics that bite (all parser-only):** 4 KB pages, the 2-byte
> entry count in the BTPAGE trailer at `4056`, and **block-level compression**
> (`isCompressedBlock`, inflated via the 4096-byte inner buffer path). ANSI vs
> Unicode differences ARE in spec: pointer width (`§2.2.1.2`) and the 12- vs
> 16-byte block trailer (`§2.2.2.8.1`).

## Encryption / obfuscation

`bCryptMethod` byte in HEADER (`[MS-PST] §2.2.2.6`), read at the variant's
encryption offset; modeled by `PstFile.Encryption`:

| Value | [MS-PST] name | Algorithm | Implementation |
|-------|---------------|-----------|----------------|
| `0x00` | `NDB_CRYPT_NONE` | none | passthrough |
| `0x01` | `NDB_CRYPT_PERMUTE` | Permutative encoding, §5.1 | `CompressibleEncryption` |
| `0x02` | `NDB_CRYPT_CYCLIC` | Cyclic encoding, §5.2 | `HighEncryption` |

Password-protected PSTs: the CRC password hash is **ignored** — it gates Outlook's
UI, not the byte layout, so parsing proceeds regardless. (CRC algorithm: §5.3;
block signature: §5.5.)

## Object model (NDB → LTP → Messaging)

MS-PST is layered; the code mirrors it.

- **NDB (Node Database), §2.2** — `PstFile`, `NodeDatabase`, `NodeEntry`,
  `BlockEntry`. HEADER (§2.2.2.6) → ROOT (§2.2.2.5) → NBT/BBT b-tree pages
  (BTPAGE §2.2.2.7.7.1, BTENTRY/BBTENTRY/NBTENTRY §2.2.2.7.7.2–.4) → blocks
  (§2.2.2.8). Data blocks §2.2.2.8.3.1; XBLOCK/XXBLOCK chain large data
  §2.2.2.8.3.2; SLBLOCK/SIBLOCK subnode trees §2.2.2.8.3.3. BID/IB/BREF are
  §2.2.2.2 / .3 / .4.
- **LTP (Lists, Tables, Properties), §2.3** — `HeapOnNode` (HN §2.3.1),
  `PropertyContext` (PC §2.3.3), `TableContext` (TC §2.3.4), `MapiProperties`,
  `NameToIdMap`. RTF bodies (`PR_RTF_COMPRESSED`) inflated by `LzFu` (LZFu).
- **Messaging, §2.4** — `Folder`, `Message`, `Attachment`, then
  `PstToEmlConverter` / `EmlSerializer` emit the `.eml` (and `ICalendarGenerator`
  for appointments).

## Current parser coverage

Self-contained snapshot — this doc is the durable home for what the parser
supports; keep it current when format support changes:

- **Formats:** ANSI (97–2002), Unicode (2003+), Unicode 2013 (2013+, beyond spec).
- **Encryption:** None, Permute, Cyclic; password-protected files parse (CRC hash
  ignored).
- **Core:** header validation + version detection; NBT/BBT b-tree parsing; PC and
  TC extraction; HeapOnNode multi-block streaming; LZFu RTF inflation.
- **Messaging:** folder-hierarchy traversal; subject/date/sender/recipients;
  text/HTML/RTF bodies (`PR_BODY` family); attachments emitted as
  `multipart/mixed` base64; duplicate skipping/suffixing.

## Section index ([MS-PST] PDF)

Jump straight to these — don't scan the spec. Page = PDF page in
`.docs/pst/[MS-PST].pdf`.

| Structure | § | Page |
|-----------|---|------|
| HEADER (incl. `wVer`, `dwMagic`, `bCryptMethod`) | 2.2.2.6 | 24 |
| ANSI vs Unicode | 2.2.1.2 | 19 |
| BID / IB / BREF | 2.2.2.2 / .3 / 2.2.2.4 | 20–21 |
| ROOT | 2.2.2.5 | 22 |
| PAGETRAILER | 2.2.2.7.1 | 28 |
| BTPAGE / BTENTRY / BBTENTRY / NBTENTRY | 2.2.2.7.7.1–.4 | 36–40 |
| Blocks / BLOCKTRAILER | 2.2.2.8 / .8.1 | 41–42 |
| Data blocks; XBLOCK / XXBLOCK | 2.2.2.8.3.1 / .3.2 | 44–46 |
| SLBLOCK / SIBLOCK | 2.2.2.8.3.3 | 48–51 |
| LTP: HN / PC / TC | 2.3.1 / 2.3.3 / 2.3.4 | 53 / 60 / 63 |
| Messaging layer | 2.4 | 70 |
| Permutative / Cyclic encode; CRC; block signature | 5.1 / 5.2 / 5.3 / 5.5 | 172–185 |

## When you touch this code

- Cite the `§` from the index above for spec-backed behavior; cite this file +
  `file:line` for what the parser does. For the **2013/4K** specifics, cite the
  parser only — there is no MS-PST passage.
- The traversal paths (SIBLOCK/XBLOCK chains, HeapOnNode streaming) carry
  recursion/size limits to bound malformed input — preserve those DoS guards when
  refactoring.

## Reading the PDF efficiently

Order of preference, cheapest first:

1. **Use this file.** The tables + section index answer almost everything without
   opening the spec.
2. **Read one section, never the whole PDF.** Look up the page in the index, then
   `Read` with a tight range, e.g. `pages: "24-25"` for HEADER. Max 20 pages/call.
   (Native PDF rendering needs poppler — `brew install poppler` — if the Read tool
   reports `pdftoppm is not installed`.)
3. **Text fallback without poppler:** extract a single section to `.tmp` (not the
   repo) with pypdf, then grep it:
   ```bash
   python3 - <<'PY'
   import pypdf
   r = pypdf.PdfReader(".docs/pst/[MS-PST].pdf")
   # pages are 0-indexed; PDF page 24 -> index 23
   print(r.pages[23].extract_text())
   PY
   ```
   Never dump all 193 pages into context — pull the section you need and discard.