-- Repeating events. A series is a template plus a rule; each occurrence is a real item, so it can be
-- done, have notes and sub-items, and be edited on its own.
create table series (
    id               uuid primary key,
    title            text        not null default '',
    notes            text        not null default '',
    main_tag_id      uuid references tags (id),
    tag_ids          uuid[]      not null default '{}',

    -- When each occurrence happens. Timed: start_time + duration_minutes, in the user's zone.
    -- All-day: span_days days starting on the occurrence date.
    all_day          boolean     not null,
    start_time       time,
    duration_minutes int,
    span_days        int,

    -- The rule, a subset of iCalendar RRULE.
    freq             text        not null check (freq in ('daily', 'weekly', 'monthly')),
    interval         int         not null default 1 check (interval between 1 and 99),
    weekdays         smallint[]  not null default '{}',   -- ISO 1 (Mon) .. 7 (Sun), weekly only
    start_date       date        not null,
    until_date       date,                                -- inclusive
    count            int check (count > 0),

    -- Occurrences exist as items up to this date; later ones are generated as time passes.
    generated_until  date,

    version          bigint      not null default 1,
    seq              bigint      not null default nextval('change_seq'),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,

    constraint timed_fields check (all_day or (start_time is not null and duration_minutes > 0)),
    constraint all_day_fields check (not all_day or span_days >= 1),
    constraint until_after_start check (until_date is null or until_date >= start_date)
);

create index series_seq on series (seq);

alter table items
    add column series_id       uuid references series (id),
    -- The date this occurrence was generated for; stays the same when the occurrence is moved.
    add column occurrence_date date,
    -- Edited on its own (time, title or tags), so series-wide edits leave it alone.
    add column detached        boolean not null default false;

-- One item per occurrence, deleted ones included: deleting an occurrence keeps it from coming back.
create unique index items_series_occurrence on items (series_id, occurrence_date) where series_id is not null;
