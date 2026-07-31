package dev.forgeci.core.validation;

/**
 * A {@code forgeci.yml} document is structurally invalid: an unknown field, a wrong type, a missing
 * required value, or a reference to an undefined task. The message always names the source file and
 * the offending location so the CLI can print it directly.
 */
public class ConfigValidationException extends RuntimeException {

    public ConfigValidationException(String message) {
        super(message);
    }
}
