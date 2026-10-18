-- Things outside the app that can be referenced (PLMS files, notices, …), refreshed by their
-- source; searched when typing @. Only titles and links: the things themselves stay at the source.
create table ref_index (
    url        text primary key,
    kind       text        not null,
    title      text        not null,
    -- Where it belongs, shown next to the title (e.g. the course).
    context    text,
    date       timestamptz,
    source     text        not null,
    updated_at timestamptz not null default now()
);

create index ref_index_source on ref_index (source);
