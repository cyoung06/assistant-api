-- With CalDAV as the store, the database keeps only what the interop layer needs to remember.

-- Every record an exporter has written, so an object the user deleted isn't recreated.
create table source_refs (
    source_type text        not null,
    ref         text        not null,
    uid         text        not null,
    created_at  timestamptz not null default now(),
    primary key (source_type, ref)
);

-- Which CalDAV calendar stands for an external grouping (e.g. a PLMS course).
create table source_calendars (
    source_type  text not null,
    external_key text not null,
    calendar_id  text not null,
    primary key (source_type, external_key)
);

-- The old items/series/tags tables stay as a backup of the pre-CalDAV data; nothing writes to them.
