-- Copies of course files made to write on (see drive/DriveAnnotations.kt): each copy, the course file
-- it came from, and that file's version when the copy last took it (a newer one offers to carry the
-- writing over). Follows either file when moved.
create table drive_annotations (
    copy_path text primary key,
    source_path text not null,
    source_etag text,
    created_at timestamptz not null
);
create index drive_annotations_source on drive_annotations (source_path);
