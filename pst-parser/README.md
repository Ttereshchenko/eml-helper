# pst-parser

A small, dependency-free Java library for reading Microsoft Outlook **PST/OST**
personal-folders files ([MS-PST]). Extracted from the MailKit IntelliJ plugin so the
PST extraction/decompression logic can be built, tested, and reused on its own.

## Requirements

- JDK 21+
- No runtime dependencies (JDK only). Tests use JUnit 5.

## Usage

```java
import com.github.ttereshchenko.mailkit.pst.*;
import java.nio.file.Path;

try (var pst = new PstFile(Path.of("archive.pst"))) {
    var root = new Folder(pst, 0x122); // NID_ROOT_FOLDER
    walk(pst, root);
}

static void walk(PstFile pst, Folder folder) throws PstException {
    for (Integer messageNid : folder.getMessages()) {
        var message = new Message(pst, messageNid);
        System.out.println(message.getSubject() + " — " + message.getSenderEmail());
    }
    for (Folder child : folder.getSubFolders()) {
        walk(pst, child);
    }
}
```

`PstFile` owns a read-only file channel and is `AutoCloseable`; always use it in a
try-with-resources block.

### Thread-safety

A `PstFile` is safe for concurrent use by multiple threads (its lazy caches are
synchronized and the channel uses positional reads). `Folder`, `Message` and
`Attachment` instances are **not** thread-safe — confine each instance to a single
thread. The natural multi-threaded pattern is one shared `PstFile` with each worker
constructing its own `Folder`/`Message` objects.

### Large files & memory

Node and block lookups descend the on-disk b-trees lazily through a small page cache,
so opening a multi-gigabyte store does not materialize its index. Attachment content
can be read fully (`Attachment.getData()`) or streamed block-by-block
(`Attachment.openDataStream()`) — prefer streaming for large payloads.

A single node's expanded data (one attachment payload, message body, …) is capped by
`maxNodeSize` (default 64 MiB, `PstFile.DEFAULT_MAX_NODE_SIZE`). Reads beyond the cap
fail with `PstException`; pass a larger cap to `new PstFile(path, maxNodeSize)` to
extract bigger attachments.

### Corrupt and password-protected stores

The header (magic, version, encryption type, b-tree roots) is validated eagerly at
open; malformed structures encountered later raise `PstException` (an `IOException`
subclass). `Folder`/`Message` constructors degrade to empty objects on damaged nodes
so bulk exports can keep walking — check `isLoaded()`/`getLoadError()` when you need
to distinguish.

Outlook's "password protection" is only a CRC stored in the message store object — the
content is **not** encrypted with it — so protected stores are read normally;
`PstFile.isPasswordProtected()` surfaces the flag for callers that want to warn.

## Public API

`PstFile`, `Folder`, `Message`, `Attachment`, `NodeEntry`, `MapiProperties`,
`PstException`. The NDB → HN → PC/TC parsing internals (`NodeDatabase`, `HeapOnNode`,
`PropertyContext`, `TableContext`, `LzFu`, …) are **package-private** implementation
detail and may change.

For callers that need to reach below the high-level model, a few public *low-level*
accessors expose raw MAPI properties and NDB nodes: `PstFile.getNode`,
`PstFile.allNodes`, `PstFile.readSubnodeEntry`, `PstFile.namedPropertyId`,
`Message.getProperty`/`getStringProperty`, and `Attachment.getNode`. Prefer the typed
getters where they exist; reach for these only when the model does not cover what you
need (e.g. orphan recovery, appointment named properties). `Message.getProperty` takes
the 16-bit property id; `PT_SYSTIME` values surface as `java.time.Instant` and
multi-valued properties as immutable `List`s.

## Module identity

The jar ships with `Automatic-Module-Name: com.github.ttereshchenko.mailkit.pst`, giving
it a stable name on the JDK module path, plus `Implementation-Title`/`Implementation-Version`
manifest entries. Sources and Javadoc jars are built alongside the binary jar.

## Supported formats

ANSI, UNICODE and UNICODE_2013 (4 KiB pages, per-block compression — OST) stores;
NONE / COMPRESSIBLE / HIGH encryption. An unrecognized encryption byte fails at open
instead of decoding garbage.

## Diagnostics

The library logs through `java.lang.System.Logger` (the JDK platform logging API), so
its diagnostics route into whatever logging backend the consuming application
configures — degraded-but-recovered conditions (a corrupt folder, an unreadable
attachment table, a failed RTF decompression) are logged at `WARNING`/`DEBUG` rather
than silently swallowed.

## Build

```bash
./gradlew :pst-parser:test :pst-parser:jar
```

[MS-PST]: https://learn.microsoft.com/openspecs/office_file_formats/ms-pst
