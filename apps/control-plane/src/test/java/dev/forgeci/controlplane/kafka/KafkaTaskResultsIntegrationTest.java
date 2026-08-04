package dev.forgeci.controlplane.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.KafkaTestContainer;
import dev.forgeci.controlplane.support.ProtocolTestClient;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Kafka is an alternate, durable ingress for {@code forge.task-results} alongside the direct HTTP
 * report — proves the two required properties from phase 5's acceptance criteria: redelivery of the
 * same message never re-applies its effect, and a message that can never be parsed ends up on the
 * dead-letter topic instead of blocking the consumer.
 */
class KafkaTaskResultsIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private TaskRunRepository taskRunRepository;

    private ProtocolTestClient client;

    @BeforeEach
    void setUp() {
        client = new ProtocolTestClient(rest);
    }

    @Test
    void aRedeliveredTaskResultMessageDoesNotReapplyItsEffect() throws Exception {
        long projectId = client.registerProject();
        String cacheKey = "sha256:kafka-idempotent-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                client.submitPlan(
                        projectId,
                        TestFixtures.singleTaskPlan(
                                "rev-kafka-1", "rev-0", "kafka-idempotent:build", cacheKey));
        BuildResponse build = client.createBuild(projectId, plan.id());
        long workerId = client.registerWorker("worker-kafka-idempotent-" + UUID.randomUUID());
        ClaimedTaskResponse task = client.claimNamed(workerId, "kafka-idempotent:build");

        TaskResultEvent event =
                new TaskResultEvent(
                        task.taskRunId(),
                        task.workerId(),
                        task.leaseToken(),
                        task.attemptId(),
                        true,
                        0,
                        null,
                        null);

        kafkaTemplate
                .send(KafkaTopics.TASK_RESULTS, String.valueOf(task.taskRunId()), event)
                .get(10, TimeUnit.SECONDS);
        client.awaitBuildState(build.id(), BuildState.SUCCEEDED);

        TaskRun afterFirstDelivery = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        var completedAtFirst = afterFirstDelivery.getCompletedAt();
        int attemptCountFirst = afterFirstDelivery.getAttemptCount();

        // redeliver the identical message — as a rebalance or an at-least-once retry would
        kafkaTemplate
                .send(KafkaTopics.TASK_RESULTS, String.valueOf(task.taskRunId()), event)
                .get(10, TimeUnit.SECONDS);
        Thread.sleep(
                1500); // give the (idle, no-op) redelivery a moment to have been processed if it
        // were going to do anything

        TaskRun afterRedelivery = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        assertThat(afterRedelivery.getState()).isEqualTo(TaskRunState.SUCCEEDED);
        assertThat(afterRedelivery.getCompletedAt()).isEqualTo(completedAtFirst);
        assertThat(afterRedelivery.getAttemptCount()).isEqualTo(attemptCountFirst);
        assertThat(client.getBuild(build.id()).state()).isEqualTo(BuildState.SUCCEEDED);
    }

    @Test
    void aMalformedTaskResultMessageIsRoutedToTheDeadLetterTopicInsteadOfBlockingTheConsumer() {
        String key = "malformed-" + UUID.randomUUID();
        String dlTopic = KafkaTopics.TASK_RESULTS + KafkaTopics.DEAD_LETTER_SUFFIX;

        try (KafkaProducer<String, String> rawProducer = rawStringProducer();
                KafkaConsumer<String, String> dlConsumer =
                        rawStringConsumer("dlt-watcher-" + UUID.randomUUID())) {
            dlConsumer.subscribe(List.of(dlTopic));
            // prime the subscription so the poll below doesn't miss the record due to partition
            // assignment happening after the send
            dlConsumer.poll(Duration.ofMillis(500));

            rawProducer.send(
                    new ProducerRecord<>(
                            KafkaTopics.TASK_RESULTS, key, "this is not valid JSON at all"));
            rawProducer.flush();

            ConsumerRecord<String, String> deadLettered =
                    KafkaTestUtils.getSingleRecord(dlConsumer, dlTopic, Duration.ofSeconds(30));
            assertThat(deadLettered.key()).isEqualTo(key);
        }
    }

    private static KafkaProducer<String, String> rawStringProducer() {
        Properties props = new Properties();
        props.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaTestContainer.INSTANCE.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<String, String> rawStringConsumer(String groupId) {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(
                        KafkaTestContainer.INSTANCE.getBootstrapServers(), groupId, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }
}
