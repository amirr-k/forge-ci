package dev.forgeci.controlplane.kafka;

/** Topic names fixed by spec/reference/contracts.md#kafka-responsibilities. */
public final class KafkaTopics {

    public static final String TASK_READY = "forge.task-ready";
    public static final String TASK_RESULTS = "forge.task-results";
    public static final String BUILD_EVENTS = "forge.build-events";

    /**
     * Suffix Spring Kafka's {@code DeadLetterPublishingRecoverer} appends by default — kept
     * explicit for tests.
     */
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    private KafkaTopics() {}
}
