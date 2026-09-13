#!/usr/bin/env bash
#
# render-diagrams.sh — re-render docs/diagrams/*.mmd to SVG via mermaid.ink.
#
# Usage:  bash scripts/render-diagrams.sh            (all diagrams)
#         bash scripts/render-diagrams.sh system-design  (one by stem)
#
# Requires: curl + python3. Edit the .mmd source, run this, commit both the
# .mmd and the .svg so the docs read in any viewer (the SVGs are embedded in
# the markdown; the .mmd stays the editable source of truth).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
mkdir -p docs/diagrams

stems=()
if [[ $# -gt 0 ]]; then
  for name in "$@"; do stems+=("$name"); done
else
  for f in docs/diagrams/*.mmd; do stems+=("$(basename "${f%.mmd}")"); done
fi

for name in "${stems[@]}"; do
  src="docs/diagrams/$name.mmd"
  [[ -f "$src" ]] || { echo "missing source: $src" >&2; exit 1; }
  b64=$(python3 -c "import base64,sys;print(base64.urlsafe_b64encode(open('$src','rb').read()).decode())")
  curl -fsSL --max-time 60 "https://mermaid.ink/svg/$b64" -o "docs/diagrams/$name.svg"
  echo "rendered docs/diagrams/$name.svg"
done
