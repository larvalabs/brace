# Releasing Brace

## Cutting a release

1. Update version in `pom.xml` (remove `-SNAPSHOT` for the release, e.g. `0.1.0`).
2. Commit: `git commit -am "Release 0.1.0"`.
3. Tag: `git tag v0.1.0`.
4. Build: `mvn clean package`.
5. Verify: `./tests/cli/test-distribution.sh` and `./tests/cli/test-self-update.sh`.
6. Publish: `gh release create v0.1.0 target/brace-0.1.0.zip --title "Brace 0.1.0" --notes "..."`.
7. Bump to next snapshot: update `pom.xml` to `0.2.0-SNAPSHOT`, commit, push.

## What's in the distribution zip

- `bin/brace` — bash launcher script
- `lib/brace-<version>.jar` — framework JAR
- `lib/*.jar` — all runtime dependencies (Jetty, Hibernate, JTE, Flyway, Jackson, jBCrypt, Jakarta Mail, JUnit Platform Console Standalone)

The zip is produced by `mvn package` via the assembly plugin configured in `src/assembly/distribution.xml`.

## Release asset contract

The installer (`install.sh`), `brace self-update`, and the per-project version
resolution in `bin/brace` all download toolchains from the GitHub Release. They
depend on this naming, so it must hold for every release:

- the release is tagged `v<version>` (e.g. `v0.1.0`)
- it has an asset named `brace-<version>.zip`
- that zip unpacks to a single top-level `brace-<version>/` directory

`mvn package` + the `Create GitHub Release` step in `.github/workflows/publish.yml`
(which attaches `target/brace-*.zip`) satisfy this automatically on a tag push.

## User install flow

Users install the launcher with the bootstrap script and put `~/.brace/bin` on PATH:

```bash
curl -fsSL https://github.com/larvalabs/brace/raw/main/install.sh | sh
export PATH="$HOME/.brace/bin:$PATH"
```

This unpacks the latest release to `~/.brace/toolchains/<version>` and links
`~/.brace/bin/brace`. Thereafter:

- `brace self-update [version]` — update the launcher (latest, or a specific version)
- `brace new <name>` — create a new project (pins its framework version in `pom.xml`)
- `brace deps` — populate project `lib/` from `pom.xml` (one time, requires Maven)
- `brace compile` / `brace run` / `brace dev` / `brace test` — dev-loop commands (no Maven needed)
- `brace ops keypair` / `brace ops dashboard` — manage ops dashboard auth

Inside a project, `brace` runs against the framework version pinned in `pom.xml`
(`<brace.version>`), fetching it to `~/.brace/toolchains/<version>` on first use —
independent of the installed launcher version.

## Dependency vulnerability gate

CI (`.github/workflows/ci.yml`, on every branch push and PR) and publishing
(`publish.yml`, before `mvn deploy` and the GitHub Release) run
`.github/scripts/osv-scan.sh`. It scans `pom.xml`'s resolved dependency tree (direct and
transitive, all scopes, test included) with a pinned, checksum-verified
[OSV-Scanner](https://google.github.io/osv-scanner/) release, and:

- **fails** on any advisory scored CVSS >= 7.0, or labelled HIGH or CRITICAL by its database
  (GitHub's advisory severity), or carrying no rating at all (unrated entries fail closed:
  they can't be shown to be below the bar);
- **lists without failing** everything lower (LOW/MODERATE, CVSS < 7.0);
- **fails** if OSV-Scanner's own resolution of the tree misses any artifact (at the exact
  version) that `mvn dependency:list` resolves, so the scan always covers what Maven builds with;
- **fails** if the scan itself errors (for example, dependency resolution fails).

The threshold rule is in `.github/scripts/osv_gate.py`. Run the same check locally from the
repo root (needs `curl`, `python3` and `mvn`; the binary is cached under `~/.cache/brace/`):

```bash
.github/scripts/osv-scan.sh                    # what CI runs
.github/scripts/osv-scan.sh --no-maven-check   # skip the mvn dependency:list cross-check
```

Run it after any dependency change. When it fails:

1. **Bump.** Upgrade the dependency (or pin the fixed transitive version in
   `<dependencyManagement>`) to a version the report lists under "fixed in", then run
   `mvn verify`. This is the expected fix.
2. **Ignore, only if unreachable.** When there is no fixed version yet, or the bump is blocked,
   and the vulnerable code is shown to be unreachable from Brace, add an entry to
   `osv-scanner.toml` at the repo root, in OSV-Scanner's own format:

   ```toml
   [[IgnoredVulns]]
   id = "GHSA-xxxx-xxxx-xxxx"   # a CVE alias also matches
   ignoreUntil = 2026-12-31     # the ignore lapses after this date and the gate fails again
   reason = "Only reachable via X, which Brace never calls (checked 2026-10-27). Bump when Y ships a fix."
   ```

   The gate refuses an entry without `ignoreUntil` or `reason`. Keep `ignoreUntil` weeks out,
   not years, and say in `reason` why it is unreachable and what unblocks the bump. The scan log prints every filtered advisory with its reason, and flags
   entries that match nothing as unused; delete those.

A newly published advisory can fail `publish.yml` on `main` or a tag even though the PR passed:
fix it as above and push again. The gate runs before `mvn deploy`, so a failed tag run has
published nothing: tag the fixed commit and push that tag.

To upgrade OSV-Scanner itself, change `OSV_SCANNER_VERSION` in `osv-scan.sh` and replace the
four pinned sums with the matching lines of that release's `osv-scanner_SHA256SUMS` asset.

## Download integrity

`install.sh`, `brace self-update` and the launcher's per-project fetch download release zips over
**https only** (redirects included); `file://` works for local test trees, and plain `http://`
is refused unless `BRACE_ALLOW_INSECURE_DOWNLOAD=1`. TLS to GitHub is what authenticates a
release: assets are **not signed**. Signing (decision D5 in the 2026-10 security review fix plan)
is deferred while Brace has no outside users: anyone able to replace a release asset can also
change `install.sh` and `bin/brace` in this repository, so a signature only helps once the
signing key is held somewhere a repository compromise doesn't reach. Revisit before outside
adoption: publish `SHA256SUMS` plus an Ed25519 signature from an offline key, and verify it in
all three download paths.
