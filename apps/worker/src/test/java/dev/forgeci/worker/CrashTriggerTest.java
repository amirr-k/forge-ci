package dev.forgeci.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CrashTriggerTest {

    @Test
    void firesTheHaltActionWhenACrashIsRequested() {
        AtomicBoolean halted = new AtomicBoolean(false);
        CrashTrigger trigger = new CrashTrigger(() -> halted.set(true));

        trigger.maybeCrash(true);

        assertTrue(halted.get());
    }

    @Test
    void doesNothingWhenNoCrashIsRequested() {
        AtomicBoolean halted = new AtomicBoolean(false);
        CrashTrigger trigger = new CrashTrigger(() -> halted.set(true));

        trigger.maybeCrash(false);

        assertFalse(halted.get());
    }
}
