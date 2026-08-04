-- Duration-aware critical-path scheduling. critical_path_weight counts hops to a sink, which
-- treats a long chain of trivial tasks as more urgent than a short chain of expensive ones. This
-- column holds the same longest-remaining-chain idea measured in estimated milliseconds instead,
-- derived from previously observed durations of the same task in the same project.
--
-- Both columns are kept: the hop-count policy remains selectable so the two can be benchmarked
-- against each other and against FIFO rather than one silently replacing another.

alter table task_runs
    add column critical_path_millis bigint not null default 0;

-- the claim query orders by this and filters on state, so the ordering is served from the index
-- instead of sorting the whole ready set on every poll
create index idx_task_runs_claim_duration
    on task_runs (state, critical_path_millis, ready_at);
