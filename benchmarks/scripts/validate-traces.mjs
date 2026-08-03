#!/usr/bin/env node
// Validates every committed trace against demo/traces/trace.schema.json.
//
// Hand-rolled rather than pulling in a JSON Schema library: this checks exactly the subset the
// schema uses (type/required/enum/const/additionalProperties/pattern/minimum/minLength/items),
// and a build-evidence contract is not worth a new transitive dependency tree.
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const schema = JSON.parse(readFileSync(join(root, "demo/traces/trace.schema.json"), "utf8"));

function check(value, node, path, errors) {
    if (node.const !== undefined && value !== node.const) {
        errors.push(`${path}: expected const ${JSON.stringify(node.const)}, got ${JSON.stringify(value)}`);
        return;
    }
    if (node.enum && !node.enum.includes(value)) {
        errors.push(`${path}: ${JSON.stringify(value)} is not one of ${node.enum.join(", ")}`);
        return;
    }
    const type = node.type;
    if (type === "object") {
        if (value === null || typeof value !== "object" || Array.isArray(value)) {
            errors.push(`${path}: expected object`);
            return;
        }
        for (const key of node.required ?? []) {
            if (!(key in value)) errors.push(`${path}: missing required property "${key}"`);
        }
        if (node.additionalProperties === false) {
            for (const key of Object.keys(value)) {
                if (!(node.properties ?? {})[key]) errors.push(`${path}: unexpected property "${key}"`);
            }
        }
        for (const [key, sub] of Object.entries(node.properties ?? {})) {
            if (key in value) check(value[key], sub, `${path}.${key}`, errors);
        }
        return;
    }
    if (type === "array") {
        if (!Array.isArray(value)) {
            errors.push(`${path}: expected array`);
            return;
        }
        if (node.items) value.forEach((item, i) => check(item, node.items, `${path}[${i}]`, errors));
        return;
    }
    if (type === "string") {
        if (typeof value !== "string") {
            errors.push(`${path}: expected string`);
            return;
        }
        if (node.pattern && !new RegExp(node.pattern).test(value)) {
            errors.push(`${path}: ${JSON.stringify(value)} does not match ${node.pattern}`);
        }
        if (node.minLength !== undefined && value.length < node.minLength) {
            errors.push(`${path}: shorter than minLength ${node.minLength}`);
        }
        return;
    }
    if (type === "integer" || type === "number") {
        if (typeof value !== "number" || (type === "integer" && !Number.isInteger(value))) {
            errors.push(`${path}: expected ${type}`);
            return;
        }
        if (node.minimum !== undefined && value < node.minimum) {
            errors.push(`${path}: ${value} is below minimum ${node.minimum}`);
        }
    }
}

// a trace whose own totals contradict its task list would validate structurally but lie to the
// demo, so the counts are cross-checked against the tasks they claim to summarize
function checkTotals(trace, errors) {
    const tally = { RUN: 0, CACHE_HIT: 0, SKIP: 0, FAILED: 0 };
    for (const task of trace.tasks) tally[task.status] += 1;
    const expected = {
        tasksExecuted: tally.RUN,
        tasksCacheHit: tally.CACHE_HIT,
        tasksSkipped: tally.SKIP,
    };
    for (const [key, want] of Object.entries(expected)) {
        if (trace.totals[key] !== want) {
            errors.push(`totals.${key} says ${trace.totals[key]} but tasks[] contains ${want}`);
        }
    }
    const ids = new Set(trace.graph.nodes.map((n) => n.id));
    for (const edge of trace.graph.edges) {
        if (!ids.has(edge.from) || !ids.has(edge.to)) {
            errors.push(`graph edge ${edge.from} -> ${edge.to} references an unknown node`);
        }
    }
    for (const task of trace.tasks) {
        if (!ids.has(task.id)) errors.push(`task ${task.id} is not a graph node`);
    }
}

const dir = join(root, "demo/traces");
const files = readdirSync(dir).filter((f) => f.endsWith(".json") && f !== "trace.schema.json");
if (files.length === 0) {
    console.error("no traces found in demo/traces/");
    process.exit(1);
}

let failed = 0;
for (const file of files) {
    const errors = [];
    let trace;
    try {
        trace = JSON.parse(readFileSync(join(dir, file), "utf8"));
    } catch (e) {
        console.error(`FAIL ${file}: not valid JSON — ${e.message}`);
        failed += 1;
        continue;
    }
    check(trace, schema, "trace", errors);
    if (errors.length === 0) checkTotals(trace, errors);
    if (errors.length > 0) {
        failed += 1;
        console.error(`FAIL ${file}`);
        for (const e of errors) console.error(`  ${e}`);
    } else {
        console.log(`ok   ${file} (${trace.scenario}, ${trace.tasks.length} tasks)`);
    }
}

if (failed > 0) {
    console.error(`\n${failed} trace(s) failed validation`);
    process.exit(1);
}
console.log(`\n${files.length} trace(s) valid`);
