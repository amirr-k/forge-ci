-- Straggler-aware speculative execution. A task run can now have more than one attempt in flight
-- at the same time: the original, plus a bounded speculative duplicate started on a different
-- worker when the original is running far past its historical duration.
--
-- That requires moving the lease off task_runs (one slot, so two live attempts could not even be
-- expressed) and onto task_attempts, one row per attempt with its own token. The task_runs lease
-- columns stay, but only as a denormalised mirror of the newest live attempt for the API and the
-- Redis reconcile path -- task_attempts is the authority every lease check reads.
--
-- winning_attempt_number is the atomicity point. The first attempt to report a result claims it
-- with a conditional update; every later report for the same generation sees a non-null winner and
-- is rejected. That is what makes duplicate execution safe to allow -- the system never promises a
-- task runs once, only that exactly one result is ever accepted. It is cleared when a run returns
-- to RETRY_WAIT, because that begins a new generation of attempts that must be able to win.

alter table task_attempts
    add column lease_token      varchar(64) null,
    add column worker_id        bigint null,
    add column lease_expiration timestamp(6) null,
    add column speculative      boolean not null default false;

alter table task_runs
    add column winning_attempt_number int null;

-- lease sweep and the "does this run still have another attempt alive?" check both scan the live
-- attempts of one task run
create index ix_task_attempts_live on task_attempts (task_run_id, state);

-- expiry sweep: live attempts whose own lease deadline has passed, independent of their task run
create index ix_task_attempts_expiration on task_attempts (state, lease_expiration);
