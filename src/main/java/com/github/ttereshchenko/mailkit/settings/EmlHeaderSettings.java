package com.github.ttereshchenko.mailkit.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

@State(name = "EmlHeaderSettings", storages = @Storage("emlHeaderSettings.xml"))
public final class EmlHeaderSettings implements PersistentStateComponent<EmlHeaderSettings.State> {
    // Canonical header-name shape, shared with the settings UI. Names are spliced into the color
    // scheme demo XML (<tag>…</tag>), so a hand-edited/corrupted state file must not smuggle in
    // characters like '<' or '&'; loadState filters against this pattern.
    static final Pattern VALID_HEADER_NAME = Pattern.compile("[A-Za-z0-9-]+");

    private State state = new State();
    private volatile Set<String> highlightedLookup = caseInsensitiveSet(state.highlightedHeaders);
    private volatile Set<String> nameOnlyLookup = caseInsensitiveSet(state.nameOnlyHeaders);

    public static EmlHeaderSettings getInstance() {
        return ApplicationManager.getApplication().getService(EmlHeaderSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        state.highlightedHeaders = sanitizeHeaderNames(state.highlightedHeaders);
        state.nameOnlyHeaders = sanitizeHeaderNames(state.nameOnlyHeaders);
        this.state = state;
        highlightedLookup = caseInsensitiveSet(state.highlightedHeaders);
        nameOnlyLookup = caseInsensitiveSet(state.nameOnlyHeaders);
    }

    private static List<String> sanitizeHeaderNames(List<String> headers) {
        return headers.stream()
                .filter(header ->
                        header != null && VALID_HEADER_NAME.matcher(header).matches())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public boolean isHighlightingEnabled() {
        return state.highlightingEnabled;
    }

    public void setHighlightingEnabled(boolean enabled) {
        state.highlightingEnabled = enabled;
    }

    public boolean isShowAttachmentActions() {
        return state.showAttachmentActions;
    }

    public void setShowAttachmentActions(boolean enabled) {
        state.showAttachmentActions = enabled;
    }

    public List<String> getHighlightedHeaders() {
        return List.copyOf(state.highlightedHeaders);
    }

    public void setHighlightedHeaders(List<String> headers) {
        state.highlightedHeaders = new ArrayList<>(headers);
        highlightedLookup = caseInsensitiveSet(state.highlightedHeaders);
    }

    public boolean isHighlighted(String headerName) {
        return highlightedLookup.contains(headerName);
    }

    public boolean isNameOnly(String headerName) {
        return nameOnlyLookup.contains(headerName);
    }

    public List<String> getNameOnlyHeaders() {
        return List.copyOf(state.nameOnlyHeaders);
    }

    public void setNameOnlyHeaders(List<String> headers) {
        state.nameOnlyHeaders = new ArrayList<>(headers);
        nameOnlyLookup = caseInsensitiveSet(state.nameOnlyHeaders);
    }

    private static Set<String> caseInsensitiveSet(Collection<String> source) {
        var set = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(source);
        return set;
    }

    public static final class State {
        // volatile: EmlSyntaxHighlighter.getTokenHighlights reads this on a background lexer thread
        // while the EDT can flip it from the settings dialog.
        public volatile boolean highlightingEnabled = true;
        public boolean showAttachmentActions = true;
        public List<String> highlightedHeaders = new ArrayList<>(List.of("From", "To", "Subject", "Date", "Cc", "Bcc"));
        public List<String> nameOnlyHeaders = new ArrayList<>(List.of("From", "To", "Subject", "Date", "Cc", "Bcc"));
    }
}
