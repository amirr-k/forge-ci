rootProject.name = "forge-ci"

include(
    ":apps:cli",
    ":apps:control-plane",
    ":apps:worker",
    ":libs:core",
    ":libs:config",
    ":libs:cache",
    ":libs:protocol",
    ":libs:test-support",
)
