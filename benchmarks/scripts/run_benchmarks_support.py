"""Shared helpers for the benchmark runner and the trace exporter."""
from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKLOAD = REPO / "demo" / "sample-monorepo"

# which source file each demo scenario edits — used to label the trace, not to decide what runs
CHANGED_FILES = {
    "leaf-module": ["services/pricing/src/main/java/PriceCalculator.java"],
    "shared-library": ["services/shared/src/main/java/Money.java"],
    "config-change": ["toolchain.lock"],
    "cold-full-build": [],
    "warm-remote-cache": [],
}


def reset_cache():
    shutil.rmtree(WORKLOAD / ".forge", ignore_errors=True)
    shutil.rmtree(WORKLOAD / "build", ignore_errors=True)


def apply_scenario(name):
    src = WORKLOAD / "scenarios" / name
    if not src.is_dir():
        return {}
    saved = {}
    for path in src.rglob("*"):
        if not path.is_file():
            continue
        target = WORKLOAD / path.relative_to(src)
        saved[target] = target.read_bytes() if target.exists() else None
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    return saved


def restore(saved):
    for target, original in saved.items():
        if original is None:
            target.unlink(missing_ok=True)
        else:
            target.write_bytes(original)


def commit():
    return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
                          capture_output=True, text=True).stdout.strip()


def environment_record(profile):
    java = subprocess.run([os.environ.get("JAVA_HOME", "") + "/bin/java", "-version"],
                          capture_output=True, text=True).stderr.splitlines()
    mem = 0
    try:
        mem = int(subprocess.run(["sysctl", "-n", "hw.memsize"],
                                 capture_output=True, text=True).stdout)
    except Exception:
        pass
    return {
        "profile": profile,
        "os": f"{platform.system()} {platform.release()}",
        "arch": platform.machine(),
        "cpuCores": os.cpu_count() or 1,
        "memoryGb": round(mem / (1024 ** 3), 1) if mem else 0,
        "javaVersion": java[0].split('"')[1] if java and '"' in java[0] else "unknown",
        "buildToolVersion": "forge 0.1.0-SNAPSHOT",
    }


def parse_forgeci_yml(path):
    """Reads the workload's task graph. The file is a fixed, flat shape — no YAML dep needed."""
    nodes, edges, deps = [], [], {}
    current, in_deps = None, False
    for raw in path.read_text().splitlines():
        task = re.match(r"^  ([\w-]+:[\w-]+):$", raw)
        if task:
            current = task.group(1)
            module = current.split(":")[0]
            nodes.append({"id": current, "module": module, "kind": current.split(":")[1]})
            deps[current] = []
            in_deps = False
            continue
        if current and re.match(r"^    depends_on:$", raw):
            in_deps = True
            continue
        if current and in_deps:
            dep = re.match(r'^      - "([^"]+)"$', raw)
            if dep:
                deps[current].append(dep.group(1))
                edges.append({"from": dep.group(1), "to": current})
                continue
            if raw.startswith("    ") and not raw.startswith("      "):
                in_deps = False
    return {
        "nodes": nodes,
        "edges": edges,
        "deps": deps,
        "modules": len({n["module"] for n in nodes}),
        "changed": CHANGED_FILES,
    }
