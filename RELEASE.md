# Releasing Brace

## Cutting a release

1. Update version in `pom.xml` (remove `-SNAPSHOT` for the release, e.g. `0.1.0`).
2. Commit: `git commit -am "Release 0.1.0"`.
3. Tag: `git tag v0.1.0`.
4. Build: `mvn clean package`.
5. Verify: `./tests/cli/test-distribution.sh`.
6. Publish: `gh release create v0.1.0 target/brace-0.1.0.zip --title "Brace 0.1.0" --notes "..."`.
7. Bump to next snapshot: update `pom.xml` to `0.2.0-SNAPSHOT`, commit, push.

## What's in the distribution zip

- `bin/brace` — bash launcher script
- `lib/brace-<version>.jar` — framework JAR
- `lib/*.jar` — all runtime dependencies (Jetty, Hibernate, JTE, Flyway, Jackson, jBCrypt, Jakarta Mail, JUnit Platform Console Standalone)

The zip is produced by `mvn package` via the assembly plugin configured in `src/assembly/distribution.xml`.

## User install flow

Users download the zip, unzip it, and put `bin/` on their PATH:

```bash
curl -LO https://github.com/larvalabs/brace/releases/download/v0.1.0/brace-0.1.0.zip
unzip brace-0.1.0.zip
export PATH="$PWD/brace-0.1.0/bin:$PATH"
```

After install, the user can run:

- `brace new <name>` — create a new project
- `brace deps` — populate project `lib/` from `pom.xml` (one time, requires Maven)
- `brace compile` / `brace run` / `brace dev` / `brace test` — dev-loop commands (no Maven needed)
- `brace ops keypair` / `brace ops dashboard` — manage ops dashboard auth
