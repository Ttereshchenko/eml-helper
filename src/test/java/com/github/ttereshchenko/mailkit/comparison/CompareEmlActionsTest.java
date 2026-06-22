package com.github.ttereshchenko.mailkit.comparison;

import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Covers the visibility/enablement logic of the Compare EML actions, including the General-settings
 * toggle that hides {@link CompareEmlWithAction} in the editor toolbar (mirrors
 * {@code SendViaSmtpActionTest}).
 */
public class CompareEmlActionsTest extends BasePlatformTestCase {

    private static final String EML = "From: a@b.c\r\n\r\nbody\r\n";

    private EmlHeaderSettings settings;
    private boolean originalShowToolbar;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        settings = EmlHeaderSettings.getInstance();
        originalShowToolbar = settings.isShowCompareEditorToolbarButton();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            settings.setShowCompareEditorToolbarButton(originalShowToolbar);
        } finally {
            super.tearDown();
        }
    }

    public void testCompareFilesVisibleForExactlyTwoEml() throws Exception {
        var first = myFixture.getTempDirFixture().createFile("a.eml", EML);
        var second = myFixture.getTempDirFixture().createFile("b.eml", EML);
        var event = event("test", ActionUiKind.NONE, first, second);

        new CompareEmlAction().update(event);

        assertTrue(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareFilesHiddenForSingleEml() throws Exception {
        var only = myFixture.getTempDirFixture().createFile("only.eml", EML);
        var event = event("test", ActionUiKind.NONE, only);

        new CompareEmlAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareFilesHiddenForMixedSelection() throws Exception {
        var eml = myFixture.getTempDirFixture().createFile("ok.eml", EML);
        var txt = myFixture.getTempDirFixture().createFile("notes.txt", "plain");
        var event = event("test", ActionUiKind.NONE, eml, txt);

        new CompareEmlAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareWithVisibleForSingleEml() throws Exception {
        var eml = myFixture.getTempDirFixture().createFile("one.eml", EML);
        var event = event("test", ActionUiKind.NONE, eml);

        new CompareEmlWithAction().update(event);

        assertTrue(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareWithHiddenForNonEml() throws Exception {
        var txt = myFixture.getTempDirFixture().createFile("notes.txt", "plain");
        var event = event("test", ActionUiKind.NONE, txt);

        new CompareEmlWithAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareWithHiddenForMultiSelection() throws Exception {
        var first = myFixture.getTempDirFixture().createFile("m1.eml", EML);
        var second = myFixture.getTempDirFixture().createFile("m2.eml", EML);
        var event = event("test", ActionUiKind.NONE, first, second);

        new CompareEmlWithAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareWithHiddenInToolbarWhenToggleOff() throws Exception {
        settings.setShowCompareEditorToolbarButton(false);
        var eml = myFixture.getTempDirFixture().createFile("bar.eml", EML);
        var event = event("EditorContextBarMenu", ActionUiKind.TOOLBAR, eml);

        new CompareEmlWithAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareWithVisibleInToolbarWhenToggleOn() throws Exception {
        settings.setShowCompareEditorToolbarButton(true);
        var eml = myFixture.getTempDirFixture().createFile("bar.eml", EML);
        var event = event("EditorContextBarMenu", ActionUiKind.TOOLBAR, eml);

        new CompareEmlWithAction().update(event);

        assertTrue(event.getPresentation().isEnabledAndVisible());
    }

    public void testCompareWithVisibleInMenuEvenWhenToolbarToggleOff() throws Exception {
        settings.setShowCompareEditorToolbarButton(false);
        var eml = myFixture.getTempDirFixture().createFile("menu.eml", EML);
        var event = event("ProjectViewPopupMenu", ActionUiKind.NONE, eml);

        new CompareEmlWithAction().update(event);

        assertTrue(event.getPresentation().isEnabledAndVisible());
    }

    private AnActionEvent event(String place, ActionUiKind uiKind, VirtualFile... files) {
        var context = SimpleDataContext.builder()
                .add(CommonDataKeys.VIRTUAL_FILE, files[0])
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, files)
                .build();
        return AnActionEvent.createEvent(context, new Presentation(), place, uiKind, null);
    }
}
