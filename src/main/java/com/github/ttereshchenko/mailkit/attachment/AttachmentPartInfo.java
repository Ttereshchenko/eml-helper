package com.github.ttereshchenko.mailkit.attachment;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

public record AttachmentPartInfo(
        @NotNull String filename,
        @NotNull ContentTransferEncoding encoding,
        @NotNull String rawBody,
        @NotNull TextRange firstLineRange) {}
