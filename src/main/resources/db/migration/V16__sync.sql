-- Notes edited live (see sync/): each one's Yjs document, and the drive file's version it last
-- matched, with the document as it was then (an edit made to the file elsewhere is merged in as an
-- edit to that state, so neither side's edits are lost).
create table sync_docs (
    path text primary key,
    state bytea not null,
    base_state bytea not null,
    file_etag text,
    updated_at timestamptz not null
);
