# Code Style

- Do not use asterisk (`*`) wildcard imports; always import types explicitly
- use methods from java.util.Objects for null checks
- use `var` for value declaration
- use unnamed variables (`_`) for unused catch parameters and unused lambda parameters
- prefer switch expressions with pattern matching over chains of `instanceof` checks
- use record patterns to destructure records in `instanceof` and `switch`
- use full words for variable names (no abbreviations like "m" for "matcher")
- don't keep unused import