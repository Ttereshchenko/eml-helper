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
import org.jetbrains.annotations.NotNull;

@State(name = "EmlHeaderSettings", storages = @Storage("emlHeaderSettings.xml"))
public final class EmlHeaderSettings implements PersistentStateComponent<EmlHeaderSettings.State> {
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
        this.state = state;
        highlightedLookup = caseInsensitiveSet(state.highlightedHeaders);
        nameOnlyLookup = caseInsensitiveSet(state.nameOnlyHeaders);
    }

    public boolean isHighlightingEnabled() {
        return state.highlightingEnabled;
    }

    public void setHighlightingEnabled(boolean enabled) {
        state.highlightingEnabled = enabled;
    }

    public List<String> getHighlightedHeaders() {
        return state.highlightedHeaders;
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
        return state.nameOnlyHeaders;
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
        public boolean highlightingEnabled = true;
        public List<String> highlightedHeaders = new ArrayList<>(List.of("From", "To", "Subject", "Date", "Cc", "Bcc"));
        public List<String> nameOnlyHeaders = new ArrayList<>(List.of("From", "To", "Subject", "Date", "Cc", "Bcc"));
    }
}
