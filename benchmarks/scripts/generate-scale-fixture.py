#!/usr/bin/env python3
"""Generates the scale benchmark workload: demo/scale-monorepo.

The 25-task sample monorepo is too small to separate one scheduling policy from another — with 4
workers its critical path and its total work are close enough that any ordering finishes in about
the same time. This fixture is deliberately larger and deliberately uneven: 50 modules over 5
dependency layers, 150 tasks, and per-module source counts that vary by an order of magnitude, so
a long pole genuinely exists and a scheduler can be right or wrong about it.

Everything it emits is real Java compiled by the same scripts/task the sample monorepo uses —
javac against the classpath of every module built earlier in the build, a class-loading
verification pass, a jar, and a content hash. No task sleeps.

Deterministic: same inputs produce byte-identical output, so the committed fixture can be
regenerated and diffed.

Usage:
    python3 benchmarks/scripts/generate-scale-fixture.py [--out demo/scale-monorepo]
"""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SAMPLE = REPO / "demo" / "sample-monorepo"

# 5 layers x 10 modules. Layer 0 has no dependencies; every later layer depends on the one before
# it, which is what gives the DAG a depth worth scheduling around.
LAYERS = 5
PER_LAYER = 10

# Source-file counts per module position within a layer. The heavy modules are placed at index 0-1
# so the dependency wiring below can thread them onto a single long chain: that chain is the
# critical path, and it is much longer than the median path rather than marginally longer.
WEIGHTS = [14, 11, 6, 5, 4, 3, 3, 2, 2, 2]


def module_name(layer: int, index: int) -> str:
    return f"l{layer}m{index:02d}"


def dependencies(layer: int, index: int) -> list[str]:
    """Each module draws from the previous layer; index 0 and 1 always chain to the previous
    layer's index 0, which is what builds one deep, heavy critical path through the graph."""
    if layer == 0:
        return []
    previous = layer - 1
    if index <= 1:
        return [module_name(previous, 0)]
    first = (index * 3) % PER_LAYER
    second = (index * 7 + 1) % PER_LAYER
    picked = sorted({first, second})
    return [module_name(previous, i) for i in picked]


def java_source(layer: int, index: int, ordinal: int, deps: list[str]) -> str:
    package = f"forge.{module_name(layer, index)}"
    calls = ""
    if deps and ordinal == 0:
        # only the first class of a module reaches across the module boundary; that single edge is
        # enough to make javac genuinely require the dependency's classes on the classpath, so a
        # change to a dependency really does force a recompile here. Referenced fully qualified
        # rather than imported: every module names its classes ApiN, so importing a dependency's
        # Api0 into this file would collide with the Api0 the file itself declares.
        calls = "".join(
            f'        parts.add("{d}:" + forge.{d}.Api0.describe().length());\n' for d in deps
        )

    helper = ""

    body_methods = "".join(
        f"""
    public long compute{n}(long seed) {{
        long acc = seed ^ {n * 2654435761 % 1000003}L;
        for (int i = 0; i < 17; i++) {{
            acc = (acc * 31 + i) ^ (acc >>> 7);
        }}
        return acc;
    }}
"""
        for n in range(6)
    )

    return f"""package {package};

import java.util.ArrayList;
import java.util.List;

/** Generated scale-fixture source. Real code so javac does real work. */
public final class Api{ordinal} {{

    public static String describe() {{
        List<String> parts = new ArrayList<>();
        parts.add("{module_name(layer, index)}#{ordinal}");
{calls}        return String.join(",", parts);
    }}
{body_methods}{helper}}}
"""


def task_block(name: str, kind: str, module: str, depends: list[str], outputs: str) -> str:
    lines = [f"  {name}:", "    environment:", '      - "JAVA_HOME"']
    if depends:
        lines.append("    depends_on:")
        lines.extend(f'      - "{d}"' for d in depends)
    lines.extend(
        [
            "    inputs:",
            '      - "scripts/task"',
            '      - "toolchain.lock"',
            f'      - "services/{module}/**"',
            "    outputs:",
            f'      - "{outputs}"',
            f'    command: ["./scripts/task", "{kind}", "{module}"]',
        ]
    )
    return "\n".join(lines) + "\n"


def generate(out: Path) -> dict:
    if out.exists():
        shutil.rmtree(out)
    (out / "services").mkdir(parents=True)
    (out / "scripts").mkdir(parents=True)

    shutil.copy2(SAMPLE / "scripts" / "task", out / "scripts" / "task")
    (out / "scripts" / "task").chmod(0o755)
    shutil.copy2(SAMPLE / "toolchain.lock", out / "toolchain.lock")

    blocks = []
    task_count = 0
    source_count = 0

    for layer in range(LAYERS):
        for index in range(PER_LAYER):
            module = module_name(layer, index)
            deps = dependencies(layer, index)
            n_sources = WEIGHTS[index] + (LAYERS - layer)

            src = out / "services" / module / "src" / "main" / "java" / "forge" / module
            src.mkdir(parents=True)
            for ordinal in range(n_sources):
                (src / f"Api{ordinal}.java").write_text(
                    java_source(layer, index, ordinal, deps), encoding="utf-8"
                )
                source_count += 1

            blocks.append(
                task_block(
                    f"{module}:build",
                    "build",
                    module,
                    [f"{d}:build" for d in deps],
                    f"build/{module}/**",
                )
            )
            blocks.append(
                task_block(
                    f"{module}:test",
                    "test",
                    module,
                    [f"{module}:build"],
                    f"build/{module}/test.txt",
                )
            )
            blocks.append(
                task_block(
                    f"{module}:package",
                    "package",
                    module,
                    [f"{module}:build"],
                    f"build/{module}/package.txt",
                )
            )
            task_count += 3

    manifest = (
        "# Generated by benchmarks/scripts/generate-scale-fixture.py — do not hand-edit.\n"
        "version: 1\n\n"
        "project:\n"
        "  name: scale-monorepo\n\n"
        "defaults:\n"
        "  timeout: 5m\n"
        "  cacheable: true\n\n"
        "tasks:\n" + "\n".join(blocks)
    )
    (out / "forgeci.yml").write_text(manifest, encoding="utf-8")

    return {"modules": LAYERS * PER_LAYER, "tasks": task_count, "sources": source_count}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(REPO / "demo" / "scale-monorepo"))
    args = parser.parse_args()
    stats = generate(Path(args.out))
    print(
        f"generated {stats['modules']} modules, {stats['tasks']} tasks, "
        f"{stats['sources']} java sources at {args.out}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
