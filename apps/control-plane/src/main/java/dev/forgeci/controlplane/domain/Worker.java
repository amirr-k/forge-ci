package dev.forgeci.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/**
 * A registered worker process. {@code externalId} is the worker's own stable identity (survives
 * restarts).
 */
@Entity
@Table(name = "workers")
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Convert(converter = StringListJsonConverter.class)
    @Column(nullable = false, columnDefinition = "json")
    private List<String> capabilities;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkerState state = WorkerState.ACTIVE;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "active_lease_count", nullable = false)
    private int activeLeaseCount;

    @Column(name = "max_concurrency", nullable = false)
    private int maxConcurrency;

    @Column(name = "version_label", nullable = false)
    private String versionLabel;

    @Column(name = "crash_requested", nullable = false)
    private boolean crashRequested;

    protected Worker() {}

    public Worker(
            String externalId, List<String> capabilities, int maxConcurrency, String versionLabel) {
        this.externalId = externalId;
        this.capabilities = capabilities;
        this.maxConcurrency = maxConcurrency;
        this.versionLabel = versionLabel;
        this.lastHeartbeatAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public WorkerState getState() {
        return state;
    }

    public void setState(WorkerState state) {
        this.state = state;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public int getActiveLeaseCount() {
        return activeLeaseCount;
    }

    public void setActiveLeaseCount(int activeLeaseCount) {
        this.activeLeaseCount = activeLeaseCount;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public String getVersionLabel() {
        return versionLabel;
    }

    public boolean isCrashRequested() {
        return crashRequested;
    }

    public void setCrashRequested(boolean crashRequested) {
        this.crashRequested = crashRequested;
    }
}
