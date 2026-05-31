package com.github.ttereshchenko.mailkit.attachment;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies attachment filenames whose extension means handing the file to the OS handler could
 * execute code or open active content — executables, installers, libraries, scripts, shortcuts,
 * macro-enabled Office formats, and active markup such as HTML/SVG. Used to warn before opening an
 * attachment decoded from untrusted mail with the system application.
 *
 * <p>The {@link FilenameSanitizer} blocks path traversal but deliberately preserves the extension,
 * so a name like {@code invoice.pdf.exe} survives intact — this guard is the second line of defense.
 */
final class DangerousAttachmentExtensions {

    private static final Set<String> DANGEROUS = Set.of(
            // Windows executables / installers / libraries
            "exe",
            "com",
            "scr",
            "pif",
            "msi",
            "msp",
            "mst",
            "cpl",
            "dll",
            "drv",
            "sys",
            "ocx",
            // Cross-platform executables / installers / packages
            "jar",
            "app",
            "dmg",
            "pkg",
            "mpkg",
            "deb",
            "rpm",
            "run",
            "bin",
            "appimage",
            "apk",
            // Shell / interpreter scripts
            "bat",
            "cmd",
            "sh",
            "bash",
            "zsh",
            "ksh",
            "csh",
            "command",
            "py",
            "pyc",
            "pyo",
            "pl",
            "rb",
            "php",
            "lua",
            "ahk",
            // Windows Script Host / PowerShell
            "vb",
            "vbs",
            "vbe",
            "js",
            "jse",
            "ws",
            "wsf",
            "wsc",
            "wsh",
            "ps1",
            "ps1xml",
            "ps2",
            "ps2xml",
            "psc1",
            "psc2",
            "msh",
            "msh1",
            "msh2",
            "mshxml",
            "msh1xml",
            "msh2xml",
            // Shortcuts / system config / registry / help
            "lnk",
            "inf",
            "reg",
            "scf",
            "url",
            "desktop",
            "hta",
            "chm",
            "hlp",
            "jnlp",
            // Macro-enabled Office formats
            "docm",
            "dotm",
            "xlsm",
            "xltm",
            "xlam",
            "pptm",
            "potm",
            "ppam",
            "ppsm",
            "sldm",
            // Active markup / scriptable documents
            "html",
            "htm",
            "xhtml",
            "xht",
            "shtml",
            "svg",
            "mht",
            "mhtml",
            "swf");

    private DangerousAttachmentExtensions() {}

    /** Whether {@code filename}'s extension is one the OS might execute or open as active content. */
    static boolean isDangerous(String filename) {
        return DANGEROUS.contains(extensionOf(filename));
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        var lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1).trim().toLowerCase(Locale.ROOT);
    }
}
