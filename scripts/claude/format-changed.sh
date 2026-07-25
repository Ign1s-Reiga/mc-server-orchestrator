#!/bin/bash
# PostToolUse(Edit|Write) hook.
# Formats only the file just edited. Skips generated CRI stubs — those are build output and
# must never be hand-formatted. Uses ktlint if present, otherwise does nothing (spotlessCheck
# catches the rest at build time).

set -uo pipefail

INPUT=$(cat)
FILE=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

[ -z "$FILE" ] && exit 0
[ -f "$FILE" ] || exit 0

# Never touch generated sources.
case "$FILE" in
  */build/generated/*|*/generated/source/proto/*) exit 0 ;;
esac

case "$FILE" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac

if command -v ktlint >/dev/null 2>&1; then
  ktlint --format --log-level=error "$FILE" >/dev/null 2>&1 || true
fi

exit 0
