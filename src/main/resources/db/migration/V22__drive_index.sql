-- The drive as last walked (see drive/DriveIndex.kt): every file and folder under Projects/, to find
-- them by name; and what each note links to (tasks, events, other files: normalized targets), for
-- backlinks. Rebuilt as the drive changes.
create table drive_index (
    path text primary key,
    name text not null,
    folder boolean not null,
    modified timestamptz,
    etag text
);
create index drive_index_name on drive_index (lower(name));

create table note_links (
    note text not null,
    target text not null,
    primary key (note, target)
);
create index note_links_target on note_links (target);
