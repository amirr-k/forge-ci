package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.TestFixtures;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ControlPlaneApiTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;

    @Test
    void registeringTheSameProjectTwiceIsIdempotent() {
        var request = TestFixtures.project();
        ProjectResponse first = rest.postForObject("/api/projects", request, ProjectResponse.class);
        ProjectResponse second = rest.postForObject("/api/projects", request, ProjectResponse.class);

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void submittingThePlanAndBuildOverHttpReturnsABuildIdAndRunsAffectedTasks() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/plans",
                        TestFixtures.twoTaskPlan("rev-1", "rev-0"),
                        PlanSubmissionResponse.class);
        assertThat(plan.taskCount()).isEqualTo(2);

        BuildResponse build =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/builds",
                        new BuildCreationRequest(plan.id(), "manual", 0),
                        BuildResponse.class);

        assertThat(build.id()).isNotNull();
        assertThat(build.state()).isEqualTo(BuildState.RUNNING);

        BuildResponse fetched = rest.getForObject("/api/builds/" + build.id(), BuildResponse.class);
        assertThat(fetched.state()).isEqualTo(BuildState.RUNNING);
    }

    @Test
    void duplicateBuildSubmissionForTheSamePlanIsIdempotent() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/plans",
                        TestFixtures.twoTaskPlan("rev-2", "rev-0"),
                        PlanSubmissionResponse.class);
        var creation = new BuildCreationRequest(plan.id(), "manual", 0);

        BuildResponse first = rest.postForObject("/api/projects/" + project.id() + "/builds", creation, BuildResponse.class);
        BuildResponse second = rest.postForObject("/api/projects/" + project.id() + "/builds", creation, BuildResponse.class);

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void resubmittingTheSamePlanRevisionIsIdempotent() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        var request = TestFixtures.twoTaskPlan("rev-3", "rev-0");

        PlanSubmissionResponse first =
                rest.postForObject("/api/projects/" + project.id() + "/plans", request, PlanSubmissionResponse.class);
        PlanSubmissionResponse second =
                rest.postForObject("/api/projects/" + project.id() + "/plans", request, PlanSubmissionResponse.class);

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void cancelingAnAlreadyTerminalBuildIsRejected() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/plans",
                        TestFixtures.twoTaskPlan("rev-4", "rev-0"),
                        PlanSubmissionResponse.class);
        BuildResponse build =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/builds",
                        new BuildCreationRequest(plan.id(), "manual", 0),
                        BuildResponse.class);

        ResponseEntity<Map> firstCancel = rest.postForEntity("/api/builds/" + build.id() + "/cancel", null, Map.class);
        assertThat(firstCancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> secondCancel = rest.postForEntity("/api/builds/" + build.id() + "/cancel", null, Map.class);
        assertThat(secondCancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void artifactsEndpointReturnsARealEmptyListRatherThanFakeData() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/plans",
                        TestFixtures.twoTaskPlan("rev-5", "rev-0"),
                        PlanSubmissionResponse.class);
        BuildResponse build =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/builds",
                        new BuildCreationRequest(plan.id(), "manual", 0),
                        BuildResponse.class);

        Object[] artifacts = rest.getForObject("/api/builds/" + build.id() + "/artifacts", Object[].class);
        assertThat(artifacts).isEmpty();
    }

    @Test
    void buildHistoryPaginatesByProject() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        for (int i = 0; i < 3; i++) {
            PlanSubmissionResponse plan =
                    rest.postForObject(
                            "/api/projects/" + project.id() + "/plans",
                            TestFixtures.twoTaskPlan("rev-page-" + i, "rev-0"),
                            PlanSubmissionResponse.class);
            rest.postForObject(
                    "/api/projects/" + project.id() + "/builds", new BuildCreationRequest(plan.id(), "manual", 0), BuildResponse.class);
        }

        Map<String, Object> page1 =
                rest.getForObject("/api/projects/" + project.id() + "/builds?page=0&size=2", Map.class);
        Map<String, Object> page2 =
                rest.getForObject("/api/projects/" + project.id() + "/builds?page=1&size=2", Map.class);

        assertThat((java.util.List<?>) page1.get("content")).hasSize(2);
        assertThat((java.util.List<?>) page2.get("content")).hasSize(1);
        assertThat(page1.get("totalElements")).isEqualTo(3);
    }

    @Test
    void healthAndReadyRespondUpWithAHealthyDatabase() {
        assertThat(rest.getForEntity("/api/health", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/ready", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
