# Setup

## 1. Drop it into the repository

Unpack the contents at the root of your repository.

```
your-repo/
├── CLAUDE.md
├── .claude/
│   ├── settings.json
│   ├── agents/          # 10 subagents
│   └── skills/          # add-server-kind / drain-protocol / generate-cri-stubs
└── scripts/claude/      # hook scripts
```

```bash
chmod +x scripts/claude/*.sh
```

Commit `.claude/`. Add `.claude/settings.local.json` and `.claude/agent-memory-local/` to `.gitignore`.

## 2. Dependencies

The hooks use `jq`. Without it, `guard-runtime.sh` fails open (blocks nothing), so install it and verify:

```bash
brew install jq      # macOS
apt install jq       # Debian/Ubuntu
which jq
```

## 3. Version note for Claude Code

If you are on a build older than 2.1.217, the `env` limits in `settings.json` may not all take effect:

- `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS` — honored from 2.1.217 (else fixed at 20)
- `CLAUDE_CODE_MAX_SUBAGENTS_PER_SESSION` — honored from 2.1.212 (else fixed at 200)
- `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH` — honored from 2.1.217 (else nesting is on, up to 5, and not configurable)

`claude update` to 2.1.217+ if you want the raised limits. Otherwise the `env` block is harmless but partly inert.

## 4. Start and verify

```bash
claude
```

- `/doctor` — check for agent-name clashes or config errors
- type `@` — the 10 agents should appear in typeahead; restart once if they do not
- `/context` — confirm CLAUDE.md is loaded

## 5. What you still need to fill in

CLAUDE.md's commands are placeholders. Adjust to the real repo, in particular:

- `scripts/dev/containerd-up.sh` / `containerd-down.sh` do not exist yet — write them, or point at however you run a local dev containerd
- Task names like `:cri:generateProto`, `:app:integrationTest`, `:app:run` should match what the build actually defines

---

# What's included

## Ten subagents

| Name | Model | Access | Role |
|---|---|---|---|
| `schema-designer` | opus / effort:high | write | YAML schema + definition types |
| `reconciler-dev` | inherit | write, worktree | Reconcile loop, scheduler, node abstraction |
| `cri-integration-dev` | inherit | write, worktree | CRI client + proto generation |
| `store-dev` | inherit | write, worktree | State persistence + store interface |
| `api-dev` | inherit | write, worktree | Dashboard-backend API |
| `test-runner` | haiku | read + Bash | Unit tests, triage |
| `integration-tester` | inherit | read + Bash | Tests against real containerd |
| `drain-auditor` | opus / effort:high | **read-only** | Stop/drain safety |
| `code-reviewer` | inherit | **read-only** | General review |
| `docs-researcher` | haiku | web + read | CRI/containerd/library research |

`schema-designer`, `reconciler-dev`, `cri-integration-dev`, `store-dev`, `api-dev`, `drain-auditor`, and `code-reviewer` carry `memory`, accumulating under `.claude/agent-memory/<name>/` — commit that too. The four implementation agents use worktree isolation, so independent tasks run concurrently.

## Three skills

- **`generate-cri-stubs`** — the proto pipeline (fetch, pin, generate, verify). This is the project's first real hurdle; the skill exists so it is not reinvented each time.
- **`add-server-kind`** — the end-to-end steps for a new server kind (schema → validation → reconcile → store → tests → integration).
- **`drain-protocol`** — the eviction state machine and failure modes. The reference files spell out the forbidden implementations, because this is where the project can be working and wrong at once.

`paper-adventure-docs` (already installed on your account) is left untouched for a future Paper/Velocity plugin. Nothing in this core references it.

## Guardrails

- **`guard-runtime.sh`** — blocks `docker` (the project depends on containerd, not Docker Engine) and broad destructive `ctr`/`nerdctl`/`crictl` operations (prune, remove-all). Targeted removal by id is allowed.
- **`format-changed.sh`** — ktlint on the single edited `.kt`/`.kts`, and never on generated CRI stubs under `build/generated`.
- `permissions.deny` blocks reads of `.env`, `secrets/`, `forwarding.secret`.

---

# Working with it

## Phase 1 — foundation (do these first, one at a time)

```
Read CLAUDE.md and scaffold the Gradle multi-module project it describes.
Modules can be empty, but build.gradle.kts and libs.versions.toml must
actually build. Have docs-researcher pin containerd, CRI proto, protoc,
grpc-java, and grpc-kotlin versions before wiring anything.
```

Verify `./gradlew build`, then:

```
Follow the generate-cri-stubs skill to stand up the :cri proto pipeline.
Have cri-integration-dev vendor the CRI proto and get
./gradlew clean :cri:generateProto :cri:build passing.
```

This is the make-or-break step. Verify a clean build generates and compiles, then commit and `/clear`.

```
Write scripts/dev/containerd-up.sh and containerd-down.sh. CLAUDE.md and
settings.json already reference these paths, so match them.
```

Commit, `/clear`.

## Phase 2 — first vertical slice

```
Follow the add-server-kind skill to add the first server kind, a plain
Paper server. Route schema to schema-designer and the loop to
reconciler-dev, and have store-dev add whatever state it needs.
```

Then verify:

```
Run test-runner, then run drain-auditor and code-reviewer in parallel.
```

Fix critical findings only, re-run test-runner, commit, `/clear`. This "implement → verify → fix critical → re-verify" loop is the unit of work.

## Phase 3 — repeat

1. Research + design together: `Have docs-researcher look up <X> and in parallel put schema-designer on <Y>.`
2. Implement: `Hand that to reconciler-dev, and in parallel have cri-integration-dev do <Z>.`
3. Verify: `Run test-runner, then drain-auditor and code-reviewer in parallel.`
4. Commit, `/clear`.

For anything touching container stop/restart, prepend: `This touches container stop. Read the drain-protocol skill first, then have drain-auditor review it.`

## What actually helps

- Always delegate research and test runs — Gradle logs and whole-repo reads burn the main context fastest.
- `/subtask` forks with full history for side tasks ("write tests for what we just changed") and reuses the prompt cache.
- Do not skip `drain-auditor`. It is the one area where code can be working and wrong.
- Watch `/tasks` for background subagents; leave them running and keep issuing instructions.
- If an agent returns walls of text, tighten the "what to return" section in its definition rather than asking each time.

## If you hit a limit

- `Concurrent subagent limit reached` → raise `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS` (needs 2.1.217+)
- `Subagent spawn limit reached` → `/clear`, or raise `CLAUDE_CODE_MAX_SUBAGENTS_PER_SESSION`
- A subagent wanting to subdivide further → raise `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH`

## Where this goes next

Subagent results funnel back to the main context, capping how many run at once. When you want long-running parallel tracks with independent contexts, look at agent teams. And when you finally build the dashboard SPA and the Paper/Velocity plugins, those are separate repos/modules with their own setup — the `paper-adventure-docs` skill is already waiting for the plugin side.
