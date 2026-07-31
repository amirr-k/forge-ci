package dev.forgeci.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "repository_identity", nullable = false)
    private String repositoryIdentity;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Project() {}

    public Project(String name, String repositoryIdentity, String defaultBranch, int configVersion) {
        this.name = name;
        this.repositoryIdentity = repositoryIdentity;
        this.defaultBranch = defaultBranch;
        this.configVersion = configVersion;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getRepositoryIdentity() {
        return repositoryIdentity;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
