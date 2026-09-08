package dev.cnba.controlplane.kafka;

/** Fixed topic names, shared by the producer, the consumers, and the tests. */
public final class KafkaTopics {

    public static final String TASK_READY = "cnba.task-ready";
    public static final String TASK_RESULTS = "cnba.task-results";
    public static final String BUILD_EVENTS = "cnba.build-events";

    /**
     * Suffix Spring Kafka's {@code DeadLetterPublishingRecoverer} appends by default — kept
     * explicit for tests.
     */
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    private KafkaTopics() {}
}
