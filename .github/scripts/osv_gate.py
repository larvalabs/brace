#!/usr/bin/env python3
"""Severity gate over OSV-Scanner JSON output (security review 2026-10, finding M12).

OSV-Scanner has no severity-threshold flag: it exits 1 on any advisory at all. This
script applies the threshold. It reads the JSON written by
`osv-scanner scan source --format json --all-packages` and exits non-zero when any
advisory group (one vulnerability plus its aliases) is:

  * scored CVSS >= 7.0 (the group's `max_severity`, which OSV-Scanner computes
    as the highest base score over the advisory's CVSS v2/v3/v4 vectors); or
  * labelled HIGH or CRITICAL by its database (`database_specific.severity`, as
    GitHub advisories carry, or the per-`affected` `database_specific` /
    `ecosystem_specific` severity some databases use instead); or
  * unrated: no CVSS score and no label at all. This fails closed, because an
    unrated entry cannot be shown to be below the threshold (malicious-package
    `MAL-` records, for one, carry no severity).

Anything else (LOW/MODERATE, CVSS < 7.0) is printed and does not fail.

With --maven-list it also checks coverage: every artifact in the given
`mvn dependency:list` output must appear, at the same version, among the packages
OSV-Scanner scanned. OSV-Scanner resolves the transitive tree itself (via deps.dev);
this cross-check fails the gate if that resolution ever diverges from Maven's.

Exit codes: 0 pass, 1 gate failed, 2 bad input.
"""

import argparse
import json
import os
import sys

CVSS_THRESHOLD = 7.0
FAILING_LABELS = {"HIGH", "CRITICAL"}


def _label(value):
    return value.strip().upper() if isinstance(value, str) and value.strip() else None


def severity_labels(vuln):
    labels = set()
    top = _label((vuln.get("database_specific") or {}).get("severity"))
    if top:
        labels.add(top)
    for affected in vuln.get("affected") or []:
        for key in ("database_specific", "ecosystem_specific"):
            label = _label((affected.get(key) or {}).get("severity"))
            if label:
                labels.add(label)
    return labels


def parse_score(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def fixed_versions(vulns, package_name):
    fixed = set()
    for vuln in vulns:
        for affected in vuln.get("affected") or []:
            if (affected.get("package") or {}).get("name") not in (None, package_name):
                continue
            for rng in affected.get("ranges") or []:
                for event in rng.get("events") or []:
                    if "fixed" in event:
                        fixed.add(event["fixed"])
    return sorted(fixed)


def findings(report):
    """Yields one finding per (package, version, advisory group), deduplicated."""
    seen = set()
    for result in report.get("results") or []:
        for pkg_entry in result.get("packages") or []:
            pkg = pkg_entry.get("package") or {}
            vulns = {v.get("id"): v for v in pkg_entry.get("vulnerabilities") or []}
            groups = [dict(g) for g in pkg_entry.get("groups") or []]
            grouped = {i for g in groups for i in g.get("ids") or []}
            # Any vulnerability outside a group (not expected) becomes its own,
            # unscored group, so it is still rated by its label or fails closed.
            for vid in vulns:
                if vid not in grouped:
                    groups.append({"ids": [vid], "aliases": [vid], "max_severity": ""})
            for group in groups:
                ids = sorted(group.get("ids") or [])
                key = (pkg.get("ecosystem"), pkg.get("name"), pkg.get("version"), tuple(ids))
                if key in seen:
                    continue
                seen.add(key)
                members = [vulns[i] for i in ids if i in vulns]
                score = parse_score(group.get("max_severity"))
                labels = set().union(*(severity_labels(v) for v in members)) if members else set()
                if score is not None and score >= CVSS_THRESHOLD:
                    failing, why = True, f"CVSS {score:.1f} >= {CVSS_THRESHOLD}"
                elif labels & FAILING_LABELS:
                    failing, why = True, "rated " + "/".join(sorted(labels & FAILING_LABELS))
                elif score is None and not labels:
                    failing, why = True, "unrated (no CVSS score or severity label): fails closed"
                else:
                    failing, why = False, "below threshold"
                aliases = sorted(set(group.get("aliases") or []) - set(ids))
                summaries = [v.get("summary") for v in members if v.get("summary")]
                yield {
                    "failing": failing,
                    "why": why,
                    "package": f"{pkg.get('name')} {pkg.get('version')} ({pkg.get('ecosystem')})",
                    "ids": ids,
                    "aliases": aliases,
                    "score": "-" if score is None else f"{score:.1f}",
                    "labels": "/".join(sorted(labels)) or "-",
                    "summary": summaries[0] if summaries else "",
                    "fixed": fixed_versions(members, pkg.get("name")),
                }


def scanned_packages(report):
    packages = set()
    for result in report.get("results") or []:
        for pkg_entry in result.get("packages") or []:
            pkg = pkg_entry.get("package") or {}
            if pkg.get("ecosystem") == "Maven":
                packages.add((pkg.get("name"), pkg.get("version")))
    return packages


def maven_packages(path):
    """Parses `mvn dependency:list -DoutputFile=...` output into (group:artifact, version)."""
    packages = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            coords = line.strip().split(" -- ", 1)[0].strip()
            parts = coords.split(":")
            # groupId:artifactId:type:version:scope, or with a classifier before the version.
            if len(parts) == 5:
                group, artifact, _type, version, _scope = parts
            elif len(parts) == 6:
                group, artifact, _type, _classifier, version, _scope = parts
            else:
                continue
            packages.add((f"{group}:{artifact}", version))
    return packages


def annotate(level, title, message):
    if os.environ.get("GITHUB_ACTIONS") == "true":
        def escape(s, prop=False):
            s = s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            return s.replace(":", "%3A").replace(",", "%2C") if prop else s
        print(f"::{level} title={escape(title, prop=True)}::{escape(message)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("report", help="OSV-Scanner JSON output (--format json --all-packages)")
    parser.add_argument("--maven-list", help="`mvn dependency:list` output file to cross-check coverage against")
    args = parser.parse_args()

    try:
        with open(args.report, encoding="utf-8") as fh:
            report = json.load(fh)
    except (OSError, ValueError) as e:
        print(f"osv-gate: cannot read {args.report}: {e}", file=sys.stderr)
        return 2

    ok = True
    scanned = scanned_packages(report)
    if not scanned:
        print("osv-gate: FAIL: the report lists no scanned Maven packages; was --all-packages passed?")
        return 1

    if args.maven_list:
        try:
            expected = maven_packages(args.maven_list)
        except OSError as e:
            print(f"osv-gate: cannot read {args.maven_list}: {e}", file=sys.stderr)
            return 2
        if not expected:
            print(f"osv-gate: FAIL: no artifacts parsed from {args.maven_list}")
            return 1
        missing = sorted(expected - scanned)
        if missing:
            ok = False
            print(f"osv-gate: FAIL: {len(missing)} artifact(s) Maven resolves were not scanned by OSV-Scanner:")
            for name, version in missing:
                print(f"  {name} {version}")
            annotate("error", "OSV coverage", f"{len(missing)} Maven-resolved artifact(s) not scanned")
        else:
            print(f"osv-gate: coverage OK: all {len(expected)} Maven-resolved artifacts were scanned.")

    results = list(findings(report))
    failing = [f for f in results if f["failing"]]
    below = [f for f in results if not f["failing"]]
    print(f"osv-gate: {len(scanned)} packages scanned, {len(results)} advisory group(s): "
          f"{len(failing)} at or above the gate (CVSS >= {CVSS_THRESHOLD} or HIGH/CRITICAL or unrated), "
          f"{len(below)} below it.")

    for heading, items, level in (("FAILING (fix: bump the dependency, or a dated ignore in osv-scanner.toml)", failing, "error"),
                                  ("Reported, not failing", below, "warning")):
        if not items:
            continue
        print(f"\n{heading}:")
        for f in items:
            ids = ", ".join(f["ids"] + f["aliases"])
            fixed = ", ".join(f["fixed"]) or "no fixed version listed"
            print(f"  {f['package']}\n    {ids}\n    CVSS {f['score']}, label {f['labels']}: {f['why']}\n"
                  f"    {f['summary']}\n    fixed in: {fixed}")
            annotate(level, f"{f['ids'][0] if f['ids'] else 'advisory'} in {f['package']}",
                     f"{f['why']}. {f['summary']} Fixed in: {fixed}")

    if failing:
        ok = False
    print("\nosv-gate: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
