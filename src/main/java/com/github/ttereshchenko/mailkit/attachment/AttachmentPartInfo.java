package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.psi.EmlBodyText;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.github.ttereshchenko.mailkit.psi.EmlNestedMessage;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public final class AttachmentPartInfo {

    private final @NotNull String filename;
    private final @NotNull ContentTransferEncoding encoding;
    private final @NotNull TextRange firstLineRange;
    private final @NotNull SmartPsiElementPointer<EmlMimePart> partPointer;
    private final int bodyStart;
    private final int bodyEnd;

    private volatile String cachedBody;

    AttachmentPartInfo(
            @NotNull String filename,
            @NotNull ContentTransferEncoding encoding,
            @NotNull TextRange firstLineRange,
            @NotNull SmartPsiElementPointer<EmlMimePart> partPointer,
            int bodyStart,
            int bodyEnd) {
        this.filename = Objects.requireNonNull(filename, "filename");
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.firstLineRange = Objects.requireNonNull(firstLineRange, "firstLineRange");
        this.partPointer = Objects.requireNonNull(partPointer, "partPointer");
        this.bodyStart = bodyStart;
        this.bodyEnd = bodyEnd;
    }

    public @NotNull String filename() {
        return filename;
    }

    public @NotNull ContentTransferEncoding encoding() {
        return encoding;
    }

    public @NotNull TextRange firstLineRange() {
        return firstLineRange;
    }

    public @NotNull String rawBody() {
        var local = cachedBody;
        if (local != null) {
            return local;
        }
        var resolved = ApplicationManager.getApplication().runReadAction((Computable<String>) this::resolveBody);
        cachedBody = resolved;
        return resolved;
    }

    private @NotNull String resolveBody() {
        var part = partPointer.getElement();
        if (part == null) {
            return "";
        }
        var file = part.getContainingFile();
        if (file == null) {
            return "";
        }
        if (bodyStart >= bodyEnd) {
            var nested = PsiTreeUtil.findChildOfAnyType(part, EmlBodyText.class, EmlNestedMessage.class);
            return nested == null ? "" : nested.getText();
        }
        var contents = file.getViewProvider().getContents();
        var end = Math.min(bodyEnd, contents.length());
        var start = Math.min(bodyStart, end);
        var slice = contents.subSequence(start, end).toString();
        return stripLeadingBlankLine(slice);
    }

    private static String stripLeadingBlankLine(String body) {
        var index = 0;
        if (index < body.length() && body.charAt(index) == '\r') {
            index++;
        }
        if (index < body.length() && body.charAt(index) == '\n') {
            index++;
        }
        return body.substring(index);
    }
}
