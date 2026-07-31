-- Phase 6: failure recovery. Backing field for the worker crash-injection admin trigger — an
-- admin/test request sets this, the worker consumes (and clears) it on its next heartbeat and
-- halts immediately, simulating a real crash mid-task.

alter table workers
    add column crash_requested boolean not null default false;
