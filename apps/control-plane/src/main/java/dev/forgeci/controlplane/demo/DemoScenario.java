package dev.forgeci.controlplane.demo;

import java.util.Set;
import java.util.stream.Stream;

/**
 * The six public-demo scenarios (product-and-demo.md#demo-repository), each mapped to the scenario
 * id {@code scripts/apply-scenario} understands and the exact paths that scenario changes — the
 * same paths {@link dev.forgeci.core.plan.PlanBuilder} uses to compute the real affected-task
 * closure, so the UI's affected/unaffected split matches what the mutation actually touched, not a
 * guess.
 */
public enum DemoScenario {
    NO_CHANGE("no-change", Set.of()),
    LEAF_MODULE_CHANGE(
            "leaf-module", Set.of("services/pricing/src/main/java/PriceCalculator.java")),
    SHARED_CORE_CHANGE("shared-core", Set.of("services/shared/src/main/java/Money.java")),
    CONFIG_TOOLCHAIN_CHANGE("config-toolchain", Set.of("toolchain.lock")),
    WORKER_CRASH("worker-crash", Set.of("services/pricing/src/main/java/PriceCalculator.java")),
    FAILED_TEST("failed-test", Set.of("services/payments/src/main/java/PaymentGateway.java"));

    private final String scriptId;
    private final Set<String> changedPaths;

    DemoScenario(String scriptId, Set<String> changedPaths) {
        this.scriptId = scriptId;
        this.changedPaths = changedPaths;
    }

    public String scriptId() {
        return scriptId;
    }

    public Set<String> changedPaths() {
        return changedPaths;
    }

    /**
     * The only guest-facing lookup — never accept a scenario id the allowlist above doesn't define.
     */
    public static DemoScenario fromScriptId(String scriptId) {
        return Stream.of(values())
                .filter(scenario -> scenario.scriptId.equals(scriptId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("unknown demo scenario: " + scriptId));
    }
}
