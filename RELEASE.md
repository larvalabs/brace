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
