#!/bin/bash
# PreToolUse(Bash) hook.
# This orchestrator manages containers itself. Guard against two mistakes during development:
#  1. Talking to Docker Engine instead of containerd (the project depends on containerd only).
#  2. ctr/nerdctl/crictl commands that destroy state broadly (prune, rm -f of everything).
#
# Exit 2 blocks the tool call and feeds stderr back to Claude.

set -uo pipefail

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

[ -z "$COMMAND" ] && exit 0

# The project must not depend on Docker Engine. Catch direct docker CLI use.
if echo "$COMMAND" | grep -qE '(^|[;&|[:space:]])docker([[:space:]]|$)'; then
  echo "Blocked: this project talks to containerd via CRI, not Docker Engine." >&2
  echo "Use ctr / nerdctl / crictl against the local containerd instead (see CLAUDE.md)." >&2
  exit 2
fi

# Block broad destructive runtime operations. Targeted removal by id/name is fine.
if echo "$COMMAND" | grep -qE '(ctr|nerdctl|crictl).*(prune|system prune)'; then
  echo "Blocked: broad prune can destroy state this orchestrator is tracking." >&2
  echo "Remove specific containers/sandboxes by id if you must." >&2
  exit 2
fi

if echo "$COMMAND" | grep -qE '(nerdctl|crictl)[[:space:]]+rm[[:space:]]+(-[a-zA-Z]*f[a-zA-Z]*[[:space:]]+)?(-a|--all)'; then
  echo "Blocked: removing all containers at once. Target specific ids instead." >&2
  exit 2
fi

exit 0
