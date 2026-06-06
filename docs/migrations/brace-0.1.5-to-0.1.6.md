# Migrating from Brace 0.1.5 → 0.1.6

This release reworks how the `brace` CLI is installed and versioned: a version-independent
launcher under `~/.brace`, per-project framework pinning, a `self-update` command, and a
switch to JitPack coordinates for generated projects. There are **no breaking changes to any
Java framework API** (handler interfaces, `Brace`, `Request`, `Result`, etc. are unchanged),
but there are **breaking changes to generated project `pom.xml` shape and to ops keypair
generation**.

## New install method: `curl | sh` bootstrap + `~/.brace` layout

Installing no longer means "download a release zip and put its `bin/` on PATH." A bootstrap
script installs a version-independent launcher under `~/.brace`:

```bash
# Before (0.1.5)
curl -LO https://github.com/larvalabs/brace/releases/latest/download/brace-0.1.1.zip
unzip brace-0.1.1.zip
export PATH="$PWD/brace-0.1.1/bin:$PATH"

# After (0.1.6)
curl -fsSL https://github.com/larvalabs/brace/raw/main/install.sh | sh
export PATH="$HOME/.brace/bin:$PATH"
```

Framework toolchains live in `~/.brace/toolchains/<version>/`, and `~/.brace/bin/brace` is a
symlink to the active one. Installer env overrides: `BRACE_DIR`, `BRACE_VERSION`,
`BRACE_RELEASE_BASE`, `BRACE_LATEST_URL`, `BRACE_MODIFY_PATH`. The old zip-on-PATH install
still works but is no longer the documented path.

## Per-project framework version pinning

The launcher is now independent of the framework version. Inside a project, `brace` resolves
the version from `<brace.version>` in `pom.xml`, downloads/caches that toolchain under
`~/.brace/toolchains/<version>` on first use, and compiles/runs/tests against it — so `brace
run` matches Maven, your IDE, and CI. A stale launcher is detected via a bootstrap-contract
check that prints:

```
Your brace launcher is older than this framework version. Run `brace self-update` to refresh it.
```

The `compile`, `run`, `dev`, `test [class]`, and `deps` commands are unchanged in name and
behavior — their implementations moved from the `bin/brace` bash script into the Java CLI and
now run against the project's pinned version.

## New command: `brace self-update [version]`

```bash
brace self-update            # update the launcher to the latest released version
brace self-update 0.1.6      # switch to / pin a specific version (downgrade allowed)
```

It downloads the target toolchain into `~/.brace/toolchains/<version>` and re-points the
`~/.brace/bin/brace` symlink. It only works for an installed launcher (it errors if
`brace.home` is not under `~/.brace/toolchains/`).

## Breaking: `brace new` generates JitPack coordinates

Generated projects are now resolvable without GitHub Packages authentication, via JitPack.
Three things changed in the generated `pom.xml`: the groupId, an added repository, and a
leading `v` on the version (JitPack resolves Git tags like `v0.1.6`).

```xml
<!-- Before (0.1.5) -->
<properties>
    <brace.version>0.1.5</brace.version>
</properties>
<dependencies>
    <dependency>
        <groupId>com.larvalabs</groupId>
        <artifactId>brace</artifactId>
        <version>${brace.version}</version>
    </dependency>
</dependencies>

<!-- After (0.1.6) -->
<properties>
    <brace.version>v0.1.6</brace.version>          <!-- note the leading "v" -->
</properties>
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
<dependencies>
    <dependency>
        <groupId>com.github.larvalabs</groupId>    <!-- was com.larvalabs -->
        <artifactId>brace</artifactId>
        <version>${brace.version}</version>
    </dependency>
</dependencies>
```

**To migrate an existing app to the JitPack coordinates:** change the groupId to
`com.github.larvalabs`, add the `jitpack.io` repository, and prefix `<brace.version>` with
`v`. (If you already publish/consume Brace via GitHub Packages and prefer to keep that, you
can stay on the old coordinates — only newly generated projects change.)

## Breaking: `brace ops keypair` rewritten

`brace ops keypair` changed its output and behavior:

- It now **writes `ops-private.key`** (gitignored; private key on line 1, public key on line
  2) instead of printing the private key to stdout, and refuses to overwrite an existing
  `ops-private.key`.
- The `ops-authorized-keys` entry is now **raw base64 with a label and no `ed25519:`
  prefix**. Before: `ed25519:<key>  key-1`. After: `<key>  <label>`.
- The default label changed from `key-1` to an identity label `<git user.email>@<hostname>`
  (falling back to the OS user). Re-running replaces the same-label entry in place instead of
  appending a duplicate.

```bash
# Before
$ brace ops keypair --label key-1
# printed private key to stdout; appended "ed25519:<key>  key-1" to ops-authorized-keys

# After
$ brace ops keypair
# wrote ops-private.key (gitignored)
# added "<key>  matt@hostname" to ops-authorized-keys
```

**Backward compatibility:** `OpsKeys.loadAuthorizedKeys` now tolerates (strips) a legacy
`ed25519:` prefix in existing `ops-authorized-keys` files, so previously generated keys keep
working. Add the new `ops-private.key` to `.gitignore`; commit `ops-authorized-keys` and
deploy it to authorize the key server-side.
