-- Initiatives group projects toward a larger goal, as in Linear (e.g. a semester holding its courses).
-- A project can be in several; deleting an initiative only unlinks its projects.
create table initiatives (
    id          uuid primary key,
    name        text        not null,
    description text        not null default '',
    status      text        not null default 'active' check (status in ('planned', 'active', 'completed')),
    target_date date,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table project_initiatives (
    project_id    uuid not null references projects (id) on delete cascade,
    initiative_id uuid not null references initiatives (id) on delete cascade,
    primary key (project_id, initiative_id)
);
create index on project_initiatives (initiative_id);
