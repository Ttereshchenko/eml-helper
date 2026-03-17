# EML Helper

An IntelliJ IDEA plugin that brings first-class support for `.eml` (Email Message format) files — syntax highlighting, code folding, and configurable header coloring.

## Features

- **Syntax highlighting** — Headers, MIME boundaries, and body content are visually distinct
- **MIME part folding** — Collapse multipart boundaries to focus on the section you care about
- **Per-header color customization** — Assign individual colors to headers like `From`, `Subject`, `Date`, etc. via **Settings > Editor > Color Scheme > EML**
- **Name-only highlighting** — Optionally highlight just the header name (e.g. `Subject:`) instead of the full line, configurable per header
- **Configurable header list** — Add or remove which headers get custom highlighting via **Settings > Editor > EML**

## Requirements

- IntelliJ IDEA 2024.3+
- Java 21+

## Installation

### From source

```bash
git clone https://github.com/Ttereshchenko/eml-helper.git
cd eml-helper
./gradlew buildPlugin
```

The plugin zip will be in `build/distributions/`. Install it via **Settings > Plugins > Install Plugin from Disk**.

### Development

```bash
./gradlew runIde        # Launch a sandboxed IDE with the plugin loaded
./gradlew compileJava   # Compile only
./gradlew test          # Run tests
```

## Usage

1. Open any `.eml` file in IntelliJ IDEA
2. Headers, boundaries, and body are automatically highlighted
3. MIME parts can be collapsed/expanded using the gutter fold icons
4. Customize header colors in **Settings > Editor > Color Scheme > EML**
5. Configure which headers are highlighted and toggle per-header name-only mode in **Settings > Editor > EML**

## License

[Apache License 2.0](LICENSE)
