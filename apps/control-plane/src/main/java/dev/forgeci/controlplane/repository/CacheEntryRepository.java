package dev.forgeci.controlplane.repository;

import dev.forgeci.controlplane.domain.CacheEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CacheEntryRepository extends JpaRepository<CacheEntry, Long> {

    Optional<CacheEntry> findByCacheKey(String cacheKey);

    Optional<CacheEntry> findByCacheKeyAndProjectId(String cacheKey, Long projectId);
}
