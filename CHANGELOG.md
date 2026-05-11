# Changelog

## [Unreleased]
### Changed
- Faster lexing, folding, and header annotation for large multipart messages (set-based boundary lookup, cached header-name resolution for RFC 822 continuation lines, case-insensitive lookup caches in EML settings).

## [0.1.0] - 2026-03-23
### Added
- Syntax highlighting for EML headers, MIME boundaries, and body content
- MIME part code folding — collapse and expand nested message parts
- Per-header color customization via the color scheme editor
- Name-only highlighting mode (highlight header name, not value)
- Global highlighting toggle under Settings → Editor → EML
- Custom header list support — add or remove headers to highlight
- RFC 822 continuation line support
