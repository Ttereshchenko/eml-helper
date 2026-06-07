package com.github.ttereshchenko.mailkit.smtp.profile;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level persistence for {@link SmtpProfile} entries and the global SMTP-egress toggle.
 * Stored in {@code smtpProfiles.xml}. Passwords + private keys live in {@link SmtpCredentialStore},
 * keyed by {@link SmtpProfile#identifier}.
 */
@State(name = "MailKitSmtpProfiles", storages = @Storage("smtpProfiles.xml"))
public final class SmtpProfileService implements PersistentStateComponent<SmtpProfileService.State> {

    private State state = new State();

    public static SmtpProfileService getInstance() {
        return ApplicationManager.getApplication().getService(SmtpProfileService.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        this.state = loaded;
    }

    public boolean isEgressEnabled() {
        return state.egressEnabled;
    }

    public void setEgressEnabled(boolean enabled) {
        state.egressEnabled = enabled;
    }

    public boolean isShowEditorToolbarButton() {
        return state.showEditorToolbarButton;
    }

    public void setShowEditorToolbarButton(boolean show) {
        state.showEditorToolbarButton = show;
    }

    public List<SmtpProfile> getProfiles() {
        var copy = new ArrayList<SmtpProfile>(state.profiles.size());
        for (var profile : state.profiles) {
            copy.add(profile.copy());
        }
        return copy;
    }

    public void setProfiles(List<SmtpProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        var rebuilt = new ArrayList<SmtpProfile>(profiles.size());
        for (var profile : profiles) {
            rebuilt.add(profile.copy());
        }
        state.profiles = rebuilt;
    }

    public Optional<SmtpProfile> findById(String profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        for (var profile : state.profiles) {
            if (profileId.equals(profile.identifier)) {
                return Optional.of(profile.copy());
            }
        }
        return Optional.empty();
    }

    public Optional<SmtpProfile> findDefault() {
        for (var profile : state.profiles) {
            if (profile.isDefault) {
                return Optional.of(profile.copy());
            }
        }
        return Optional.empty();
    }

    /** Add or replace a profile (matched by id). The first added profile becomes the default. */
    public void upsert(SmtpProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.identifier == null || profile.identifier.isBlank()) {
            profile.identifier = UUID.randomUUID().toString();
        }
        var existingIndex = indexOf(profile.identifier);
        var snapshot = profile.copy();
        if (existingIndex >= 0) {
            state.profiles.set(existingIndex, snapshot);
        } else {
            state.profiles.add(snapshot);
            if (state.profiles.size() == 1) {
                snapshot.isDefault = true;
            }
        }
    }

    public void remove(String profileId) {
        var index = indexOf(profileId);
        if (index < 0) {
            return;
        }
        var wasDefault = state.profiles.get(index).isDefault;
        state.profiles.remove(index);
        if (wasDefault && !state.profiles.isEmpty()) {
            state.profiles.get(0).isDefault = true;
        }
    }

    public void setDefault(String profileId) {
        for (var profile : state.profiles) {
            profile.isDefault = profileId != null && profileId.equals(profile.identifier);
        }
    }

    private int indexOf(String profileId) {
        if (profileId == null) {
            return -1;
        }
        for (var index = 0; index < state.profiles.size(); index++) {
            if (profileId.equals(state.profiles.get(index).identifier)) {
                return index;
            }
        }
        return -1;
    }

    public static final class State {
        public boolean egressEnabled = true;
        public boolean showEditorToolbarButton = true;
        public List<SmtpProfile> profiles = new ArrayList<>();
    }
}
