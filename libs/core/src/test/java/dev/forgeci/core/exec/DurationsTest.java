package dev.forgeci.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurationsTest {

    @Test
    void parsesEverySupportedUnit() {
        assertEquals(Duration.ofMillis(250), Durations.parse("250ms"));
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
        assertEquals(Duration.ofMinutes(10), Durations.parse("10m"));
        assertEquals(Duration.ofHours(2), Durations.parse("2h"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "10", "m", "10 m", "-5s", "10minutes", "1.5s"})
    void rejectsAnythingElse(String value) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Durations.parse(value));

        assertEquals(
                "invalid duration '" + value + "' (expected a number followed by ms, s, m, or h)",
                failure.getMessage());
    }

    /** A digit string can be arithmetically valid and still overflow every unit conversion. */
    @ParameterizedTest
    @ValueSource(strings = {"99999999999999999999s", "9223372036854775807h", "9000h"})
    void rejectsADurationTooLongToRepresent(String value) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Durations.parse(value));

        assertEquals(
                "duration '" + value + "' is too long (the maximum is 8760h)", failure.getMessage());
    }

    @Test
    void formatsForOperators() {
        assertEquals("0.4s", Durations.format(Duration.ofMillis(400)));
        assertEquals("12.0s", Durations.format(Duration.ofSeconds(12)));
        assertEquals("1m5.0s", Durations.format(Duration.ofSeconds(65)));
    }
}
