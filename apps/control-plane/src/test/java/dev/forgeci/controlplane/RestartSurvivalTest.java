package dev.forgeci.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.PlanSubmission;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.service.BuildService;
import dev.forgeci.controlplane.service.PlanSubmissionService;
import dev.forgeci.controlplane.service.ProjectService;
import dev.forgeci.controlplane.support.MySqlTestContainer;
import dev.forgeci.controlplane.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Simulates a control-plane process restart: a build is created against one application context,
 * that context is torn down (as a process exit would be), and a brand-new context — pointed at the
 * same MySQL instance — must see the same accepted state. No in-memory state is relied on.
 */
class RestartSurvivalTest {

    private ConfigurableApplicationContext startContext() {
        // command-line args, not .properties() (those are low-priority defaults application.yml wins over)
        return new SpringApplicationBuilder(ControlPlaneApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + MySqlTestContainer.INSTANCE.getJdbcUrl(),
                        "--spring.datasource.username=" + MySqlTestContainer.INSTANCE.getUsername(),
                        "--spring.datasource.password=" + MySqlTestContainer.INSTANCE.getPassword());
    }

    @Test
    void persistedBuildStateSurvivesAControlPlaneRestart() {
        Long buildId;
        try (ConfigurableApplicationContext firstRun = startContext()) {
            ProjectService projectService = firstRun.getBean(ProjectService.class);
            PlanSubmissionService planSubmissionService = firstRun.getBean(PlanSubmissionService.class);
            BuildService buildService = firstRun.getBean(BuildService.class);

            Project project = projectService.register(TestFixtures.project());
            PlanSubmission plan = planSubmissionService.submit(project.getId(), TestFixtures.twoTaskPlan("restart-1", "rev-0"));
            Build build = buildService.createBuild(project.getId(), new BuildCreationRequest(plan.getId(), "manual", 0));
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
