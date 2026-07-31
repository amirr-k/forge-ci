package dev.forgeci.controlplane.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.KafkaTestContainer;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Kafka is an alternate, durable ingress for {@code forge.task-results} alongside the direct HTTP
 * report — proves the two required properties from phase 5's acceptance criteria: redelivery of
 * the same message never re-applies its effect, and a message that can never be parsed ends up on
 * the dead-letter topic instead of blocking the consumer.
 */
class KafkaTaskResultsIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private TaskRunRepository taskRunRepository;

    @Test
    void aRedeliveredTaskResultMessageDoesNotReapplyItsEffect() throws Exception {
        long projectId = registerProject();
        String cacheKey = "sha256:kafka-idempotent-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.singleTaskPlan("rev-kafka-1", "rev-0", "solo:build", cacheKey),
                        PlanSubmissionResponse.class);
        BuildResponse build = createBuild(projectId, plan.id());
        long workerId = registerWorker("worker-kafka-idempotent-" + UUID.randomUUID());
        ClaimedTaskResponse task = claimMine(workerId, "solo:build");

        TaskResultEvent event =
                new TaskResultEvent(task.taskRunId(), task.workerId(), task.leaseToken(), task.attemptId(), true, 0, null, null);

        kafkaTemplate.send(KafkaTopics.TASK_RESULTS, String.valueOf(task.taskRunId()), event).get(10, TimeUnit.SECONDS);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        TaskRun afterFirstDelivery = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        var completedAtFirst = afterFirstDelivery.getCompletedAt();
        int attemptCountFirst = afterFirstDelivery.getAttemptCount();

        // redeliver the identical message — as a rebalance or an at-least-once retry would
        kafkaTemplate.send(KafkaTopics.TASK_RESULTS, String.valueOf(task.taskRunId()), event).get(10, TimeUnit.SECONDS);
        Thread.sleep(1500); // give the (idle, no-op) redelivery a moment to have been processed if it were going to do anything

        TaskRun afterRedelivery = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        assertThat(afterRedelivery.getState()).isEqualTo(dev.forgeci.controlplane.domain.TaskRunState.SUCCEEDED);
        assertThat(afterRedelivery.getCompletedAt()).isEqualTo(completedAtFirst);
        assertThat(afterRedelivery.getAttemptCount()).isEqualTo(attemptCountFirst);
        assertThat(getBuild(build.id()).state()).isEqualTo(BuildState.SUCCEEDED);
    }

    @Test
    void aMalformedTaskResultMessageIsRoutedToTheDeadLetterTopicInsteadOfBlockingTheConsumer() {
        String key = "malformed-" + UUID.randomUUID();
        String dlTopic = KafkaTopics.TASK_RESULTS + KafkaTopics.DEAD_LETTER_SUFFIX;

        try (KafkaProducer<String, String> rawProducer = rawStringProducer();
                KafkaConsumer<String, String> dlConsumer = rawStringConsumer("dlt-watcher-" + UUID.randomUUID())) {
            dlConsumer.subscribe(List.of(dlTopic));
            // prime the subscription so the poll below doesn't miss the record due to partition
            // assignment happening after the send
            dlConsumer.poll(Duration.ofMillis(500));

            rawProducer.send(new ProducerRecord<>(KafkaTopics.TASK_RESULTS, key, "this is not valid JSON at all"));
            rawProducer.flush();

            ConsumerRecord<String, String> deadLettered = KafkaTestUtils.getSingleRecord(dlConsumer, dlTopic, Duration.ofSeconds(30));
            assertThat(deadLettered.key()).isEqualTo(key);
        }
    }

    private ClaimedTaskResponse claimMine(long workerId, String taskName) {
        for (int i = 0; i < 100; i++) {
            var response = rest.postForEntity("/api/workers/" + workerId + "/claim", null, ClaimedTaskResponse.class);
            if (response.getStatusCode().value() == 200 && response.getBody() != null) {
                if (response.getBody().taskName().equals(taskName)) {
                    return response.getBody();
                }
                // foreign leftover from another test's global-queue backlog — harmlessly complete it
                var report =
                        new dev.forgeci.protocol.TaskResultReportRequest(
                                response.getBody().workerId(), response.getBody().leaseToken(), response.getBody().attemptId(), true, 0, null, null);
                rest.postForEntity("/api/task-runs/" + response.getBody().taskRunId() + "/result", report, Void.class);
                continue;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("worker " + workerId + " never claimed " + taskName);
    }

    private void awaitBuildState(Long buildId, BuildState expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (getBuild(buildId).state() == expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("build " + buildId + " never reached " + expected);
    }

    private long registerProject() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        return project.id();
    }

    private BuildResponse createBuild(long projectId, Long planSubmissionId) {
        return rest.postForObject(
                "/api/projects/" + projectId + "/builds", new BuildCreationRequest(planSubmissionId, "manual", 0), BuildResponse.class);
    }

    private BuildResponse getBuild(Long buildId) {
        return rest.getForObject("/api/builds/" + buildId, BuildResponse.class);
    }

    private long registerWorker(String externalId) {
        WorkerRegistrationResponse response =
                rest.postForObject(
                        "/api/workers/register", new WorkerRegistrationRequest(externalId, List.of(), 1, "test"), WorkerRegistrationResponse.class);
        return response.workerId();
    }

    private static KafkaProducer<String, String> rawStringProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.INSTANCE.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<String, String> rawStringConsumer(String groupId) {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(KafkaTestContainer.INSTANCE.getBootstrapServers(), groupId, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }
}
