package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.BuildEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildEventRepository extends JpaRepository<BuildEvent, Long> {

    List<BuildEvent> findByBuildIdOrderBySequenceNumberAsc(Long buildId);

    long countByBuildId(Long buildId);
}
