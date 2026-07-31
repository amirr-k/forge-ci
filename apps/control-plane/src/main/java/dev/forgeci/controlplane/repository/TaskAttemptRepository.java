package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.TaskAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, Long> {

    List<TaskAttempt> findByTaskRunIdOrderByAttemptNumber(Long taskRunId);
}
