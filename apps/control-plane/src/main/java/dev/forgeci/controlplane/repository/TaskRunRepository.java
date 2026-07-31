package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    List<TaskRun> findByBuildId(Long buildId);

    Optional<TaskRun> findByBuildIdAndTaskName(Long buildId, String taskName);

    long countByState(TaskRunState state);
}
