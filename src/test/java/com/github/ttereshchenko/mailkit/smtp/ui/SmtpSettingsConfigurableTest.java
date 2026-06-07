package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;

public class SmtpSettingsConfigurableTest extends BasePlatformTestCase {

    private SmtpProfileService service;
    private boolean originalEgress;
    private boolean originalShowToolbar;
    private List<SmtpProfile> originalProfiles;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = SmtpProfileService.getInstance();
        originalEgress = service.isEgressEnabled();
        originalShowToolbar = service.isShowEditorToolbarButton();
        originalProfiles = service.getProfiles();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            service.setEgressEnabled(originalEgress);
            service.setShowEditorToolbarButton(originalShowToolbar);
            service.setProfiles(originalProfiles);
        } finally {
            super.tearDown();
        }
    }

    public void testCreateComponentRendersWithoutError() {
        var configurable = new SmtpSettingsConfigurable();
        try {
            assertNotNull(configurable.createComponent());
            assertNotNull(configurable.tableModelForTests());
            assertNotNull(configurable.egressCheckboxForTests());
            assertNotNull(configurable.showEditorToolbarCheckboxForTests());
        } finally {
            configurable.disposeUIResources();
        }
    }

    public void testTogglesReflectServiceState() {
        service.setEgressEnabled(false);
        service.setShowEditorToolbarButton(false);
        var configurable = new SmtpSettingsConfigurable();
        try {
            configurable.createComponent();
            assertFalse(configurable.egressCheckboxForTests().isSelected());
            assertFalse(configurable.showEditorToolbarCheckboxForTests().isSelected());
        } finally {
            configurable.disposeUIResources();
        }
    }

    public void testApplyPersistsTogglesAndProfiles() throws Exception {
        var configurable = new SmtpSettingsConfigurable();
        try {
            configurable.createComponent();
            configurable.egressCheckboxForTests().setSelected(false);
            configurable.showEditorToolbarCheckboxForTests().setSelected(false);
            var profiles = configurable.editedProfilesForTests();
            var profile = new SmtpProfile();
            profile.name = "added";
            profile.host = "smtp.example.com";
            profiles.add(profile);

            configurable.apply();

            assertFalse(service.isEgressEnabled());
            assertFalse(service.isShowEditorToolbarButton());
            var stored = service.getProfiles();
            assertTrue(stored.stream().anyMatch(candidate -> "added".equals(candidate.name)));
        } finally {
            configurable.disposeUIResources();
        }
    }

    public void testResetReloadsFromService() {
        service.setEgressEnabled(false);
        service.setShowEditorToolbarButton(false);
        service.setProfiles(new ArrayList<>());
        var seeded = new SmtpProfile();
        seeded.name = "seed";
        seeded.host = "seed.example.com";
        service.upsert(seeded);

        var configurable = new SmtpSettingsConfigurable();
        try {
            configurable.createComponent();
            configurable.egressCheckboxForTests().setSelected(true);
            configurable.showEditorToolbarCheckboxForTests().setSelected(true);
            configurable.editedProfilesForTests().clear();

            configurable.reset();

            assertFalse(configurable.egressCheckboxForTests().isSelected());
            assertFalse(configurable.showEditorToolbarCheckboxForTests().isSelected());
            assertEquals(1, configurable.editedProfilesForTests().size());
            assertEquals("seed", configurable.editedProfilesForTests().get(0).name);
        } finally {
            configurable.disposeUIResources();
        }
    }
}
