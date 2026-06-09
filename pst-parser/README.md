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
try-with-resources block. Instances are **not** thread-safe — confine a `PstFile` to a
single thread.

## Public API

`PstFile`, `Folder`, `Message`, `Attachment`, `NodeEntry`, `MapiProperties`,
`PstException`. The NDB → HN → PC/TC parsing internals (`NodeDatabase`, `HeapOnNode`,
`PropertyContext`, `TableContext`, `LzFu`, …) are implementation detail and may change.

## Supported formats

ANSI, UNICODE and UNICODE_2013 stores; NONE / COMPRESSIBLE / HIGH encryption.

## Build

```bash
./gradlew :pst-parser:test :pst-parser:jar
```

[MS-PST]: https://learn.microsoft.com/openspecs/office_file_formats/ms-pst
