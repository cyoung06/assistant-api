-- Files kept with tasks (e.g. a shared screenshot). Small, so they live in Postgres for now; an
-- upload not attached to a task within a day is removed.
create table attachments (
    id           uuid primary key,
    task_id      uuid references tasks (id) on delete cascade,
    filename     text        not null,
    content_type text        not null,
    size         int         not null,
    data         bytea       not null,
    created_at   timestamptz not null default now()
);

create index attachments_task on attachments (task_id);
