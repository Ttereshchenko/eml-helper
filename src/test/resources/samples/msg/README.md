# MSG test fixtures

The MSG → EML converter is exercised against fixtures generated in-memory by
`MsgFixtureBuilder` (in `src/test/java/.../conversion/msg/`). The builder emits
minimal but valid OLE2 compound documents straight to a `byte[]`, which the
tests feed to `MsgToEmlConverter.convert(InputStream)`.

This approach keeps the test corpus deterministic and reproducible — no opaque
binaries to maintain — and lets each test express its preconditions inline
(subject, sender, recipients, attachments) rather than relying on a black-box
sample. The five scenarios named in the feature spec are covered by
`MsgToEmlConverterTest`:

| Scenario          | Test method                                  |
|-------------------|----------------------------------------------|
| `plain.msg`       | `plainMessageEmitsRequiredHeaders`           |
| `html.msg`        | `htmlBodyDrivesContentType`                  |
| `attachment.msg`  | `attachmentRoundTripsFilenameAndBytes`       |
| `embedded.msg`    | `embeddedMsgIsRecursedIntoMessageRfc822`     |
| `unicode.msg`     | `unicodeSubjectIsRfc2047Encoded`             |

If you need an on-disk `.msg` for manual smoke testing inside the sandboxed IDE
(`./gradlew runIde`), the simplest path is to drag an email out of Outlook,
drop the resulting `.msg` here, and re-run.
