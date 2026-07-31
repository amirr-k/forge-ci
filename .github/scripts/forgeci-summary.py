#!/usr/bin/env python3
"""Parses `forge plan` and `forge run` text output into one machine-readable JSON summary.

Both commands' output formats are fixed and covered by apps/cli's own tests
(PlanCommandTest, RunCommandTest) — this script is a thin, disposable reader
of that stable contract, not a second source of truth for it.
"""
import json
import re
import sys


def parse_plan(text: str) -> dict:
    run_tasks, cached_tasks = [], []
    section = None
    for line in text.splitlines():
        if line == "Affected tasks":
            section = "run"
            continue
        if line == "Reused tasks":
            section = "cached"
            continue
        if not line.startswith("  ") or line.strip() == "":
            if line and not line.startswith("  "):
                section = None
            continue
        if section == "run":
            match = re.match(r"^  (\S+)\s+RUN\s+(.+)$", line)
            if match:
                run_tasks.append({"name": match.group(1), "reason": match.group(2)})
        elif section == "cached":
            match = re.match(r"^  (\S+)\s+CACHED\s*$", line)
            if match:
                cached_tasks.append({"name": match.group(1)})

    summary_match = re.search(r"^Plan: (\d+) run, (\d+) cached, (\d+) unaffected$", text, re.MULTILINE)
    return {
        "run": run_tasks,
        "cached": cached_tasks,
        "counts": {
            "run": int(summary_match.group(1)) if summary_match else len(run_tasks),
            "cached": int(summary_match.group(2)) if summary_match else len(cached_tasks),
            "unaffected": int(summary_match.group(3)) if summary_match else 0,
        },
    }


STATUSES = ("SUCCEEDED", "FAILED", "TIMED_OUT", "SKIPPED", "CANCELED")

# Mirrors RunCommand's fixed-width row format ("  %-24s %-10s %8s  %s"): sliced by column,
# not split on whitespace, because a cache-hit row's detail text ("restored from cache")
# itself contains spaces and must not bleed into the elapsed column.
def parse_run(text: str) -> dict:
    tasks = []
    for line in text.splitlines():
        if not line.startswith("  ") or len(line) < 38:
            continue
        status = line[27:37].strip()
        if status not in STATUSES:
            continue
        name = line[2:26].strip()
        elapsed = line[38:46].strip() or None
        detail = line[48:].strip() or None
        tasks.append({"name": name, "status": status, "elapsed": elapsed, "detail": detail})

    summary_match = re.search(
        r"^Run: (\d+) succeeded, (\d+) failed, (\d+) skipped in (\S+)$", text, re.MULTILINE
    )
    return {
        "tasks": tasks,
        "counts": {
            "succeeded": int(summary_match.group(1)) if summary_match else 0,
            "failed": int(summary_match.group(2)) if summary_match else 0,
            "skipped": int(summary_match.group(3)) if summary_match else 0,
            "wall_clock": summary_match.group(4) if summary_match else None,
        },
    }


def main() -> int:
    if len(sys.argv) != 5:
        print(
            f"usage: {sys.argv[0]} <plan-output-file> <run-output-file> <base-revision> <out-json>",
            file=sys.stderr,
        )
        return 2

    plan_file, run_file, base_revision, out_file = sys.argv[1:5]

    with open(plan_file, encoding="utf-8") as f:
        plan_text = f.read()
    with open(run_file, encoding="utf-8") as f:
        run_text = f.read()

    plan = parse_plan(plan_text)
    run = parse_run(run_text)

    summary = {
        "schema_version": 1,
        "base_revision": base_revision,
        "plan": plan,
        "run": run,
        "ok": run["counts"]["failed"] == 0,
    }

    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
        f.write("\n")

    print(json.dumps(summary["plan"]["counts"] | {"run_result": summary["run"]["counts"]}, indent=2))
    return 0 if summary["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
