package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.Artifact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    Optional<Artifact> findByDigest(String digest);

    List<Artifact> findByDigestIn(List<String> digests);
}
