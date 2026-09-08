package dev.cnba.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cnba.controlplane.api.dto.BuildCreationRequest;
import dev.cnba.controlplane.domain.Build;
import dev.cnba.controlplane.domain.BuildState;
import dev.cnba.controlplane.domain.PlanSubmission;
import dev.cnba.controlplane.domain.Project;
import dev.cnba.controlplane.service.BuildService;
import dev.cnba.controlplane.service.PlanSubmissionService;
import dev.cnba.controlplane.service.ProjectService;
import dev.cnba.controlplane.support.MinioTestContainer;
import dev.cnba.controlplane.support.MySqlTestContainer;
import dev.cnba.controlplane.support.RedisTestContainer;
import dev.cnba.controlplane.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Simulates a control-plane process restart: a build is created against one application context,
 * that context is torn down (as a process exit would be), and a brand-new context — pointed at the
 * same MySQL instance — must see the same accepted state. No in-memory state is relied on.
 */
@Tag("integration")
class RestartSurvivalTest {

    private ConfigurableApplicationContext startContext() {
        // command-line args, not .properties() (those are low-priority defaults application.yml
        // wins over)
        return new SpringApplicationBuilder(ControlPlaneApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + MySqlTestContainer.INSTANCE.getJdbcUrl(),
                        "--spring.datasource.username=" + MySqlTestContainer.INSTANCE.getUsername(),
                        "--spring.datasource.password=" + MySqlTestContainer.INSTANCE.getPassword(),
                        "--cnba.artifacts.s3.endpoint-override="
                                + MinioTestContainer.INSTANCE.getS3URL(),
                        "--cnba.artifacts.s3.access-key="
                                + MinioTestContainer.INSTANCE.getUserName(),
                        "--cnba.artifacts.s3.secret-key="
                                + MinioTestContainer.INSTANCE.getPassword(),
                        "--cnba.artifacts.s3.bucket=cnba-artifacts-restart-test",
                        // pinned to the shared container, not the application.yml default: an
                        // ambient
                        // localhost Redis is exactly the kind of hidden dependency that passes on a
                        // developer's machine and fails on a clean runner
                        "--spring.data.redis.host=" + RedisTestContainer.host(),
                        "--spring.data.redis.port=" + RedisTestContainer.port());
    }

    @Test
    void persistedBuildStateSurvivesAControlPlaneRestart() {
        Long buildId;
        try (ConfigurableApplicationContext firstRun = startContext()) {
            ProjectService projectService = firstRun.getBean(ProjectService.class);
            PlanSubmissionService planSubmissionService =
                    firstRun.getBean(PlanSubmissionService.class);
            BuildService buildService = firstRun.getBean(BuildService.class);

            Project project = projectService.register(TestFixtures.project());
            PlanSubmission plan =
                    planSubmissionService.submit(
                            project.getId(), TestFixtures.twoTaskPlan("restart-1", "rev-0"));
            Build build =
                    buildService.createBuild(
                            project.getId(), new BuildCreationRequest(plan.getId(), "manual", 0));
            buildId = build.getId();
        }

        try (ConfigurableApplicationContext secondRun = startContext()) {
            BuildService buildService = secondRun.getBean(BuildService.class);
            Build reloaded = buildService.get(buildId);

            assertThat(reloaded.getState()).isEqualTo(BuildState.RUNNING);
            assertThat(reloaded.getRevision()).isEqualTo("restart-1");
        }
    }
}
