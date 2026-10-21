-- What was deleted from the drive (see drive/DriveTrash.kt): moved to .Trash/<id>/ there, kept 30
-- days, restorable to where it was.
create table drive_trash (
    id uuid primary key,
    path text not null,
    name text not null,
    folder boolean not null,
    size bigint not null,
    deleted_at timestamptz not null
);
create index drive_trash_deleted_at on drive_trash (deleted_at);
