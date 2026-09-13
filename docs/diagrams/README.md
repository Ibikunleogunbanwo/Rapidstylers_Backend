# docs/diagrams — rendered system diagrams

SVG renders of the system-design diagrams, embedded in the markdown docs so they
read in any viewer (GitHub, editors, plain markdown renderers) — not just on
GitHub's mermaid renderer.

| File | Used by |
|---|---|
| `system-design.svg` / `.mmd` | `docs/duplication-guide.md` §0.1 — system at a glance |
| `ci-cd-pipeline.svg` / `.mmd` | `docs/ci-cd.md` §2 — pipeline at a glance |
| `duplication-topology.svg` / `.mmd` | `deploy/README.md` — full duplication picture |
| `ai-pipeline.svg` / `.mmd` | AI/LLM process flow — ingestion → embeddings → retrieval → LLM → response |

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
