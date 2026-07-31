-- Phase 5: distributed workers. Extends task_definitions with the execution details a worker
-- needs (command/outputs/environment/timeout) and task_runs with the fields the worker protocol
-- and scheduler require (lease token, readiness/retry timestamps, critical-path priority).

alter table task_definitions
    add column command      json not null,
    add column outputs      json not null,
    add column environment  json not null,
    add column timeout_seconds int not null default 120;

alter table task_runs
    add column lease_token          varchar(64) null,
    add column ready_at             timestamp(6) null,
    add column retry_at             timestamp(6) null,
    add column critical_path_weight int not null default 0;

alter table workers
    add column max_concurrency int not null default 1;

-- scheduler claim query: dependency-complete (READY), highest critical-path weight first, then
-- FIFO by the time the task actually became ready.
create index ix_task_runs_schedule on task_runs (state, critical_path_weight desc, ready_at);

-- retry sweep: RETRY_WAIT tasks whose backoff has elapsed.
create index ix_task_runs_retry_at on task_runs (state, retry_at);
