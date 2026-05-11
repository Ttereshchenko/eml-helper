package com.github.ttereshchenko.emlhelper.folding;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class EmlFoldingBuilder extends FoldingBuilderEx {
    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary\\s*=\\s*\"?([^\"\\s;]+)\"?", Pattern.CASE_INSENSITIVE);

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root, @NotNull Document doc, boolean quick) {
        if (quick) return new FoldingDescriptor[0];

        var text = doc.getText();
        var boundaries = new ArrayList<String>();
        var matcher = BOUNDARY_PATTERN.matcher(text);
        while (matcher.find()) {
            var boundary = matcher.group(1);
            if (!boundaries.contains(boundary)) boundaries.add(boundary);
        }

        var descriptors = new ArrayList<FoldingDescriptor>();
        var lineCount = doc.getLineCount();

        for (String boundary : boundaries) {
            var startMarker = "--" + boundary;
            var endMarker   = "--" + boundary + "--";

            // Collect positions of all boundary lines for this boundary
            var markerLines = new ArrayList<int[]>(); // [lineIndex, type: 0=start, 1=end]
            for (int i = 0; i < lineCount; i++) {
                var lineStart = doc.getLineStartOffset(i);
                var lineEnd = doc.getLineEndOffset(i);
                var line = text.substring(lineStart, lineEnd).stripTrailing();
                if (line.equals(endMarker)) {
                    markerLines.add(new int[]{i, 1});
                } else if (line.equals(startMarker)) {
                    markerLines.add(new int[]{i, 0});
                }
            }

            // Create fold for each MIME part: from after --boundary\n to before next marker
            for (int idx = 0; idx < markerLines.size() - 1; idx++) {
                var current = markerLines.get(idx);
                var next    = markerLines.get(idx + 1);

                // Only fold after a start marker
                if (current[1] != 0) continue;

                var currentLineIdx = current[0];
                var nextLineIdx    = next[0];

                // Content starts at beginning of line after --boundary
                var contentStart = doc.getLineEndOffset(currentLineIdx);
                if (contentStart < doc.getTextLength() &&
                        doc.getCharsSequence().charAt(contentStart) == '\n') {
                    contentStart++;
                }

                // Content ends at start of next marker line
                var contentEnd = doc.getLineStartOffset(nextLineIdx);

                if (contentEnd > contentStart) {
                    descriptors.add(new FoldingDescriptor(
                            root.getNode(),
                            new TextRange(contentStart, contentEnd)
                    ));
                }
            }
        }

        return descriptors.toArray(new FoldingDescriptor[0]);
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        return "...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
