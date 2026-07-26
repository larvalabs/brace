# Periodic Model Reviews

Brace gets a full-codebase review in each of four categories whenever a notably more
capable frontier model becomes available. The premise: every model generation can find
real issues the previous one missed, so a review pass is repeated, not because the code
changed, but because the reviewer got better. Each review produces a findings doc, a fix
branch with one commit per finding, and a permanent record in this directory.

## Categories

| Category | What it looks for | Findings prefix |
|---|---|---|
| **Security** | Injection, authn/authz gaps, crypto misuse, DoS, information disclosure, across HTTP lifecycle, sessions, ops surface, database, files/CLI | `fix(security):` |
| **Correctness** | Plain bugs: wrong results, silently dropped or corrupted data, unbounded growth, work lost rather than retried, behavior contradicting its own docs | `fix(correctness):` |
| **Token Efficiency** | How much it costs an agent to build and operate apps on Brace: API shapes that force boilerplate, doc weight/staleness, verbose tool output, patterns agents repeat per-route | `feat(tokens):` / `docs(tokens):` |
| **Runtime Performance** | Hot-path allocation, lock contention, query patterns, startup time, memory footprint under load | `perf:` |

A correctness review overlaps the other three at the edges — a leak is also a perf problem, a
traversal bug is also a security bug. The tie-breaker is the reason the finding is listed: if
the code produces a *wrong answer* (or loses data, or grows without bound) it belongs here,
whatever its secondary flavor.

## Process

1. **Review.** Run the new model over the full codebase for one category. Multi-dimension
   sweep (for security: crypto/sessions, HTTP lifecycle, ops surface, database/injection,
   files/CLI). Verify every High-severity finding directly against source before listing it.
2. **Findings doc.** Write `docs/<date>-<category>-review-todos.md`: one checkbox per
   finding with an ID (`H1`, `M3`, `L7`…), severity, affected files/lines, a concrete fix
   spec, and a suggested model assignment sized by how subtle the fix is (mechanical →
   smaller/cheaper model, protocol/invariant changes → frontier model). The checkboxes in
   that doc are the canonical tracker.
3. **Fix branch.** One branch per review (e.g. `security-review-2026-06`), one commit per
   finding: `fix(security): <ID> <summary>` with a body referencing the findings doc. Each
   commit ticks its checkbox and passes the full `mvn test` suite; user-visible changes get
   migration-guide entries per AGENTS.md.
4. **Record.** When the branch is done, add `docs/reviews/<YYYY-MM>-<category>-<model>.md`
   capturing scope, findings summary, the commit list, validation status, and follow-ups
   that fell out of the work. Update the index below.
5. **Merge gate.** Full `mvn verify` (includes the real-Postgres Testcontainers tier;
   `mvn test` alone is H2-only) plus a code-review pass over the branch diff.

## Review index

| Date | Category | Model | Record | Findings doc |
|---|---|---|---|---|
| 2026-06 | Security | Fable 5 | [2026-06-security-fable-5.md](2026-06-security-fable-5.md) | [security-review-todos](../2026-06-09-security-review-todos.md) |
| 2026-06 | Token Efficiency | Fable 5 | [2026-06-token-efficiency-fable-5.md](2026-06-token-efficiency-fable-5.md) | [token-efficiency-review-todos](../2026-06-11-token-efficiency-review-todos.md) |
| 2026-06 | Runtime Performance | Fable 5 | [2026-06-runtime-performance-fable-5.md](2026-06-runtime-performance-fable-5.md) | [runtime-performance-review-todos](../2026-06-11-runtime-performance-review-todos.md) |
| 2026-07 | Correctness | Opus 5 | [2026-07-correctness-opus-5.md](2026-07-correctness-opus-5.md) | [correctness-review-todos](../2026-07-24-correctness-review-todos.md) |

The 2026-06-11 token-efficiency review formalizes and supersedes an informal 2026-06-10
pass that came out of the ai-benchmark project's review of agent-generated Brace apps
(commit `a8c96eb`) and was filed straight into `TODO.md`; those seed findings were
verified, re-quantified, and folded into the findings doc.
