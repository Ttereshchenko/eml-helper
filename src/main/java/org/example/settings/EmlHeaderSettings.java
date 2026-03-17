package org.example.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@State(name = "EmlHeaderSettings", storages = @Storage("emlHeaderSettings.xml"))
public final class EmlHeaderSettings implements PersistentStateComponent<EmlHeaderSettings.MyState> {
    private MyState myState = new MyState();

    public static EmlHeaderSettings getInstance() {
        return ApplicationManager.getApplication().getService(EmlHeaderSettings.class);
    }

    @Override
    public @NotNull MyState getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull MyState state) {
        myState = state;
    }

    public List<String> getHighlightedHeaders() {
        return myState.highlightedHeaders;
    }

    public void setHighlightedHeaders(List<String> headers) {
        myState.highlightedHeaders = new ArrayList<>(headers);
    }

    public boolean isHighlighted(String headerName) {
        for (String h : myState.highlightedHeaders) {
            if (h.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        return false;
    }

    public boolean isNameOnly(String headerName) {
        for (String h : myState.nameOnlyHeaders) {
            if (h.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        return false;
    }

    public List<String> getNameOnlyHeaders() {
        return myState.nameOnlyHeaders;
    }

    public void setNameOnlyHeaders(List<String> headers) {
        myState.nameOnlyHeaders = new ArrayList<>(headers);
    }

    public static final class MyState {
        public List<String> highlightedHeaders = new ArrayList<>(List.of(
                "From", "To", "Subject", "Date", "Cc", "Bcc"
        ));
        public List<String> nameOnlyHeaders = new ArrayList<>(List.of(
                "From", "To", "Subject", "Date", "Cc", "Bcc"
        ));
    }
}
