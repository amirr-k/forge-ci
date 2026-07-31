-- ForgeCI control-plane schema v1.
-- MySQL is authoritative for accepted build/task state (see spec/reference/contracts.md).

create table projects (
    id                bigint auto_increment primary key,
    name              varchar(255) not null,
    repository_identity varchar(512) not null,
    default_branch    varchar(255) not null,
    config_version    int not null,
    created_at        timestamp(6) not null default current_timestamp(6),
    constraint uq_projects_name unique (name)
) engine=innodb;

-- one row per accepted `forge plan` submission; task_definitions below hang off this version
create table plan_submissions (
    id                  bigint auto_increment primary key,
    project_id          bigint not null,
    revision            varchar(255) not null,
    base_revision       varchar(255) not null,
    full_build          boolean not null,
    changed_paths       json not null,
    unaffected_tasks    json not null,
    submitted_at        timestamp(6) not null default current_timestamp(6),
    constraint fk_plan_submissions_project foreign key (project_id) references projects (id),
    constraint uq_plan_submissions_revision unique (project_id, revision, base_revision)
) engine=innodb;

create table task_definitions (
    id                  bigint auto_increment primary key,
    plan_submission_id  bigint not null,
    task_name           varchar(255) not null,
    depends_on          json not null,
    cache_key           varchar(255) not null,
    reason              varchar(1024) not null,
    constraint fk_task_definitions_plan foreign key (plan_submission_id) references plan_submissions (id),
    constraint uq_task_definitions_name unique (plan_submission_id, task_name)
) engine=innodb;

create table builds (
    id                  bigint auto_increment primary key,
    project_id          bigint not null,
    plan_submission_id  bigint not null,
    revision            varchar(255) not null,
    base_revision       varchar(255) not null,
    trigger_type        varchar(64) not null,
    state               varchar(32) not null,
    requested_worker_count int not null default 0,
    created_at          timestamp(6) not null default current_timestamp(6),
    started_at          timestamp(6) null,
    completed_at        timestamp(6) null,
    version             bigint not null default 0,
    constraint fk_builds_project foreign key (project_id) references projects (id),
    constraint fk_builds_plan_submission foreign key (plan_submission_id) references plan_submissions (id),
    -- resubmitting a build for the same accepted plan is idempotent: no duplicate logical build
    constraint uq_builds_plan_submission unique (project_id, plan_submission_id)
) engine=innodb;

create index ix_builds_project_created on builds (project_id, created_at);

create table task_runs (
    id                  bigint auto_increment primary key,
    build_id            bigint not null,
    task_name           varchar(255) not null,
    cache_key           varchar(255) not null,
    state               varchar(32) not null,
    attempt_count       int not null default 0,
    worker_id           bigint null,
    lease_expiration    timestamp(6) null,
    started_at          timestamp(6) null,
    completed_at        timestamp(6) null,
    exit_code           int null,
    artifact_digest     varchar(128) null,
    failure_reason      varchar(1024) null,
    version             bigint not null default 0,
    constraint fk_task_runs_build foreign key (build_id) references builds (id),
    constraint uq_task_runs_task_name unique (build_id, task_name)
) engine=innodb;

create index ix_task_runs_ready_retry on task_runs (state);

create table task_attempts (
    id                  bigint auto_increment primary key,
    task_run_id         bigint not null,
    attempt_number      int not null,
    state               varchar(32) not null,
    started_at          timestamp(6) null,
    completed_at        timestamp(6) null,
    exit_code           int null,
    failure_reason      varchar(1024) null,
    constraint fk_task_attempts_task_run foreign key (task_run_id) references task_runs (id),
    constraint uq_task_attempts_number unique (task_run_id, attempt_number)
) engine=innodb;

create index ix_task_attempts_task_run on task_attempts (task_run_id);

create table artifacts (
    id                  bigint auto_increment primary key,
    digest              varchar(128) not null,
    object_store_key    varchar(1024) not null,
    size_bytes          bigint not null,
    checksum_algorithm  varchar(32) not null,
    manifest_version    int not null,
    created_at          timestamp(6) not null default current_timestamp(6),
    producer_cache_key  varchar(255) not null,
    constraint uq_artifacts_digest unique (digest)
) engine=innodb;

create table cache_entries (
    id                  bigint auto_increment primary key,
    cache_key           varchar(255) not null,
    artifact_id         bigint not null,
    project_id          bigint not null,
    created_at          timestamp(6) not null default current_timestamp(6),
    constraint fk_cache_entries_artifact foreign key (artifact_id) references artifacts (id),
    constraint fk_cache_entries_project foreign key (project_id) references projects (id),
    constraint uq_cache_entries_cache_key unique (cache_key)
) engine=innodb;

create table workers (
    id                  bigint auto_increment primary key,
    external_id         varchar(255) not null,
    capabilities         json not null,
    state               varchar(32) not null,
    last_heartbeat_at   timestamp(6) null,
    active_lease_count  int not null default 0,
    version_label       varchar(64) not null,
    constraint uq_workers_external_id unique (external_id)
) engine=innodb;

create table build_events (
    id                  bigint auto_increment primary key,
    build_id            bigint not null,
    sequence_number     bigint not null,
    event_type          varchar(64) not null,
    task_run_id         bigint null,
    occurred_at         timestamp(6) not null default current_timestamp(6),
    payload             json not null,
    constraint fk_build_events_build foreign key (build_id) references builds (id),
    constraint fk_build_events_task_run foreign key (task_run_id) references task_runs (id),
    constraint uq_build_events_sequence unique (build_id, sequence_number)
) engine=innodb;
