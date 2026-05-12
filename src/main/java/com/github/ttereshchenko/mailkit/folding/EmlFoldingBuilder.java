package com.github.ttereshchenko.mailkit.folding;

import com.github.ttereshchenko.mailkit.lexer.EmlBoundaryParser;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlFoldingBuilder extends FoldingBuilderEx {
    private static final FoldingDescriptor[] EMPTY = new FoldingDescriptor[0];

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root, @NotNull Document doc, boolean quick) {
        if (quick) {
            return EMPTY;
        }

        var text = doc.getCharsSequence();
        var boundaries = EmlBoundaryParser.collect(text);
        if (boundaries.isEmpty()) {
            return EMPTY;
        }

        var markersByBoundary = new HashMap<String, List<Marker>>();
        var lineCount = doc.getLineCount();
        for (int lineIdx = 0; lineIdx < lineCount; lineIdx++) {
            var lineStart = doc.getLineStartOffset(lineIdx);
            var lineEnd = doc.getLineEndOffset(lineIdx);
            var line = text.subSequence(lineStart, lineEnd).toString().stripTrailing();

            String boundaryName;
            Marker.Kind kind;
            if (boundaries.isEnd(line)) {
                boundaryName = line.substring(2, line.length() - 2);
                kind = Marker.Kind.END;
            } else if (boundaries.isStart(line)) {
                boundaryName = line.substring(2);
                kind = Marker.Kind.START;
            } else {
                continue;
            }
            markersByBoundary
                    .computeIfAbsent(boundaryName, _ -> new ArrayList<>())
                    .add(new Marker(lineIdx, kind));
        }

        var descriptors = new ArrayList<FoldingDescriptor>();
        var totalLength = doc.getTextLength();
        for (var markers : markersByBoundary.values()) {
            for (int idx = 0; idx < markers.size() - 1; idx++) {
                var current = markers.get(idx);
                if (current.kind() != Marker.Kind.START) {
                    continue;
                }
                var next = markers.get(idx + 1);
                var contentStart = contentStartOffset(doc, current.lineIdx(), totalLength);
                var contentEnd = doc.getLineStartOffset(next.lineIdx());
                if (contentEnd > contentStart) {
                    descriptors.add(new FoldingDescriptor(root.getNode(), new TextRange(contentStart, contentEnd)));
                }
            }
        }

        return descriptors.toArray(FoldingDescriptor[]::new);
    }

    private static int contentStartOffset(Document doc, int startLineIdx, int totalLength) {
        var nextLineIdx = startLineIdx + 1;
        if (nextLineIdx < doc.getLineCount()) {
            return doc.getLineStartOffset(nextLineIdx);
        }
        return totalLength;
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        return "...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }

    private record Marker(int lineIdx, Kind kind) {
        enum Kind {
            START,
            END
        }
    }
}
