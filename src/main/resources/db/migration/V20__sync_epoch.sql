-- Which document a path's live state is (see sync/SyncDocs.kt): made with it, kept when it moves. A
-- device keeps it with its copy, and drops that copy when the path's document isn't the same one any
-- more (deleted and made again, or replaced): merged in, it would bring back what was there before.
alter table sync_docs add column epoch uuid not null default gen_random_uuid();
