# docs/diagrams — diagram sources

Mermaid sources (`.mmd`) for the diagrams in `docs/`. The docs draw them inline
in a mermaid block, which GitHub and most Markdown previews render. Render an
`.svg` only if you need a plain image (for slides, or a viewer without mermaid).

| File | Used by |
|---|---|
| `target-architecture.mmd` | `docs/target-architecture.md` — the target design (rendered inline by GitHub) |
| `cloudflare-request-path.mmd` | `docs/cloudflare.md` — how a request reaches the app (rendered inline by GitHub) |

## Workflow

The `.mmd` files are the **editable source of truth**. If you change one, paste
the same text into the mermaid block of the doc that shows it.

```bash
# Edit a .mmd, then re-render (all, or one by stem):
bash scripts/render-diagrams.sh            # all
bash scripts/render-diagrams.sh cloudflare-request-path   # one
```

If you render an `.svg`, commit it with its `.mmd` so they never drift. The render uses [mermaid.ink](https://mermaid.ink) (`curl` + `python3`
only — no node/chromium download needed).
