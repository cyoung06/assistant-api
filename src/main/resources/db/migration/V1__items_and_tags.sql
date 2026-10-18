-- Every write to a syncable row takes the next value from this sequence.
-- Clients pull changes with GET /changes?since=<seq>.
create sequence change_seq;

create table tags (
    id         uuid primary key,
    name       text        not null,
    color      text        not null check (color ~ '^#[0-9A-Fa-f]{6}$'),
    version    bigint      not null default 1,
    seq        bigint      not null default nextval('change_seq'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

create unique index tags_name_unique on tags (lower(name)) where deleted_at is null;
create index tags_seq on tags (seq);

create table items (
    id            uuid primary key,
    title         text        not null default '',
    notes         text        not null default '',

    -- "by": deadline. A null time means end of that day.
    by_date       date,
    by_time       time,

    -- "at": scheduled time. Either timed (at_start/at_end) or all-day
    -- (at_start_date/at_end_date, end date inclusive), never both.
    at_start      timestamptz,
    at_end        timestamptz,
    at_start_date date,
    at_end_date   date,

    parent_id     uuid references items (id),
    main_tag_id   uuid references tags (id),
    tag_ids       uuid[]      not null default '{}',
    done_at       timestamptz,

    source_type   text        not null default 'manual'
                  check (source_type in ('manual', 'plms', 'email', 'upload')),
    source_ref    text,
    source_url    text,

    version       bigint      not null default 1,
    seq           bigint      not null default nextval('change_seq'),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    deleted_at    timestamptz,

    constraint by_time_needs_date check (by_time is null or by_date is not null),
    constraint at_timed_pair check ((at_start is null) = (at_end is null)),
    constraint at_all_day_pair check ((at_start_date is null) = (at_end_date is null)),
    constraint at_one_kind check (at_start is null or at_start_date is null),
    constraint at_timed_order check (at_end > at_start),
    constraint at_all_day_order check (at_end_date >= at_start_date),
    constraint not_own_parent check (parent_id <> id)
);

create index items_parent on items (parent_id) where deleted_at is null;
create index items_seq on items (seq);
create index items_by_date on items (by_date) where deleted_at is null;
create index items_at_timed on items (at_start, at_end) where deleted_at is null;
create index items_at_all_day on items (at_start_date, at_end_date) where deleted_at is null;
create index items_tag_ids on items using gin (tag_ids);
-- One item per external source record (e.g. one PLMS assignment, one email).
create unique index items_source_unique on items (source_type, source_ref)
    where source_ref is not null and deleted_at is null;
