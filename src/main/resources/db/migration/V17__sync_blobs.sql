-- What live notebooks point to by content (see sync/Blobs.kt): their base PDFs and the pictures
-- placed on their pages, kept once each whatever refers to them.
create table sync_blobs (
    hash text primary key,
    mime text not null,
    data bytea not null,
    created_at timestamptz not null
);
