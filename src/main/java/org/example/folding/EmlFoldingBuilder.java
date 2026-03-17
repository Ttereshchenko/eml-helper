package org.example.folding;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmlFoldingBuilder extends FoldingBuilderEx {
    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary\\s*=\\s*\"?([^\"\\s;]+)\"?", Pattern.CASE_INSENSITIVE);

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root, @NotNull Document doc, boolean quick) {
        if (quick) return FoldingDescriptor.EMPTY;

        String text = doc.getText();
        List<String> boundaries = new ArrayList<>();
        Matcher m = BOUNDARY_PATTERN.matcher(text);
        while (m.find()) {
            String b = m.group(1);
            if (!boundaries.contains(b)) boundaries.add(b);
        }

        List<FoldingDescriptor> descriptors = new ArrayList<>();
        int lineCount = doc.getLineCount();

        for (String boundary : boundaries) {
            String startMarker = "--" + boundary;
            String endMarker   = "--" + boundary + "--";

            // Collect positions of all boundary lines for this boundary
            List<int[]> markerLines = new ArrayList<>(); // [lineIndex, type: 0=start, 1=end]
            for (int i = 0; i < lineCount; i++) {
                int ls = doc.getLineStartOffset(i);
                int le = doc.getLineEndOffset(i);
                String line = text.substring(ls, le).stripTrailing();
                if (line.equals(endMarker)) {
                    markerLines.add(new int[]{i, 1});
                } else if (line.equals(startMarker)) {
                    markerLines.add(new int[]{i, 0});
                }
            }

            // Create fold for each MIME part: from after --boundary\n to before next marker
            for (int idx = 0; idx < markerLines.size() - 1; idx++) {
                int[] current = markerLines.get(idx);
                int[] next    = markerLines.get(idx + 1);

                // Only fold after a start marker
                if (current[1] != 0) continue;

                int currentLineIdx = current[0];
                int nextLineIdx    = next[0];

                // Content starts at beginning of line after --boundary
                int contentStart = doc.getLineEndOffset(currentLineIdx);
                if (contentStart < doc.getTextLength() &&
                        doc.getCharsSequence().charAt(contentStart) == '\n') {
                    contentStart++;
                }

                // Content ends at start of next marker line
                int contentEnd = doc.getLineStartOffset(nextLineIdx);

                if (contentEnd > contentStart) {
                    descriptors.add(new FoldingDescriptor(
                            root.getNode(),
                            new TextRange(contentStart, contentEnd)
                    ));
                }
            }
        }

        return descriptors.toArray(FoldingDescriptor.EMPTY);
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
