package dev.forgeci.core.glob;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlobMatcherTest {

    @Test
    void doubleStarCrossesDirectoryBoundaries() {
        assertTrue(
                GlobMatcher.matches(
                        "services/pricing/**",
                        "services/pricing/src/main/java/PriceCalculator.java"));
        assertTrue(GlobMatcher.matches("services/pricing/**", "services/pricing/go.mod"));
    }

    @Test
    void singleStarStaysWithinOneSegment() {
        assertTrue(GlobMatcher.matches("apps/web/*.ts", "apps/web/config.ts"));
        assertFalse(GlobMatcher.matches("apps/web/*.ts", "apps/web/src/config.ts"));
    }

    @Test
    void literalPathsMustMatchExactly() {
        assertTrue(GlobMatcher.matches("go.mod", "go.mod"));
        assertFalse(GlobMatcher.matches("go.mod", "sub/go.mod"));
    }

    @Test
    void unrelatedPathsDoNotMatch() {
        assertFalse(GlobMatcher.matches("services/pricing/**", "services/checkout/File.java"));
    }
}
