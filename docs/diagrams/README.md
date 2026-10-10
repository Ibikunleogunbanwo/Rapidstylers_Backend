# docs/diagrams — rendered system diagrams

SVG renders of the system-design diagrams, embedded in the markdown docs so they
read in any viewer (GitHub, editors, plain markdown renderers) — not just on
GitHub's mermaid renderer.

| File | Used by |
|---|---|
| `system-design.svg` / `.mmd` | System at a glance (not embedded in a doc right now) |
| `ci-cd-pipeline.svg` / `.mmd` | CI/CD pipeline at a glance (not embedded in a doc right now) |
| `duplication-topology.svg` / `.mmd` | `deploy/README.md` — full duplication picture |

## Workflow

The `.mmd` files are the **editable source of truth**; the `.svg` files are what
the docs embed.

```bash
# Edit a .mmd, then re-render (all, or one by stem):
bash scripts/render-diagrams.sh            # all
bash scripts/render-diagrams.sh system-design   # one
```

Commit the `.mmd` and the `.svg` together so the diagram and its render never
drift. The render uses [mermaid.ink](https://mermaid.ink) (`curl` + `python3`
only — no node/chromium download needed).
