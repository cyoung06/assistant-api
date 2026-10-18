-- Status of each data source plugin (one row per source type, e.g. 'plms').
create table sources (
    type            text primary key,
    last_sync_at    timestamptz,
    last_success_at timestamptz,
    last_error      text,
    -- Counts from the last successful sync: {"created": 2, "updated": 1, "completed": 0, ...}
    last_report     jsonb
);

-- Which tag stands for an external grouping, e.g. a PLMS course. Keyed by the source's own ID so
-- renaming or recoloring the tag in the app doesn't break the link.
create table source_tags (
    source_type  text not null,
    external_key text not null,
    tag_id       uuid not null references tags (id),
    primary key (source_type, external_key)
);
