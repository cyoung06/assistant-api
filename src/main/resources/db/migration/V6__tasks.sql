-- The Tasks sub-app: Linear-style tasks with projects, labels and relations. Postgres is the
-- source of truth; the feed exports them as iCalendar VTODO.

create table projects (
    id          uuid primary key,
    name        text        not null,
    description text        not null default '',
    color       text        not null check (color ~ '^#[0-9A-F]{6}$'),
    state       text        not null default 'in_progress'
                check (state in ('backlog', 'planned', 'in_progress', 'paused', 'completed', 'canceled')),
    -- Set when a source owns the project (e.g. a PLMS course); the key is the source's own ID.
    source_type text,
    source_key  text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create unique index projects_source on projects (source_type, source_key) where source_key is not null;

create table labels (
    id         uuid primary key,
    name       text        not null,
    color      text        not null check (color ~ '^#[0-9A-F]{6}$'),
    created_at timestamptz not null default now()
);

create unique index labels_name on labels (lower(name));

create sequence task_number;

create table tasks (
    id           uuid primary key,
    number       bigint      not null unique default nextval('task_number'),
    title        text        not null,
    description  text        not null default '',
    status       text        not null default 'todo'
                 check (status in ('backlog', 'todo', 'in_progress', 'done', 'canceled')),
    -- Linear's scale: 0 none, 1 urgent, 2 high, 3 medium, 4 low.
    priority     smallint    not null default 0 check (priority between 0 and 4),
    due_date     date,
    due_time     time,
    project_id   uuid references projects (id) on delete set null,
    parent_id    uuid references tasks (id) on delete cascade,
    -- When it was last closed (done or canceled); null while open.
    closed_at    timestamptz,
    source_type  text,
    -- The source's reference, e.g. "plms:assign:123"; one task per reference.
    source_ref   text unique,
    source_url   text,
    version      bigint      not null default 1,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    constraint due_time_needs_date check (due_time is null or due_date is not null),
    constraint not_own_parent check (parent_id <> id)
);

create index tasks_parent on tasks (parent_id);
create index tasks_project on tasks (project_id);
create index tasks_due on tasks (due_date);

create table task_labels (
    task_id  uuid not null references tasks (id) on delete cascade,
    label_id uuid not null references labels (id) on delete cascade,
    primary key (task_id, label_id)
);

-- "blocks": task blocks related. "related": either way. "duplicate": task is a duplicate of related.
create table task_relations (
    id         uuid primary key,
    type       text not null check (type in ('blocks', 'related', 'duplicate')),
    task_id    uuid not null references tasks (id) on delete cascade,
    related_id uuid not null references tasks (id) on delete cascade,
    created_at timestamptz not null default now(),
    unique (type, task_id, related_id),
    constraint not_self check (task_id <> related_id)
);

create index task_relations_related on task_relations (related_id);
