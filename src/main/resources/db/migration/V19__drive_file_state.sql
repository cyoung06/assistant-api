-- What's kept about each drive file for the person (see drive/DriveFileState.kt): starred, when last
-- opened, and where a PDF was left (page, zoom, tool). It follows the file when moved or renamed.
create table drive_file_state (
    path text primary key,
    starred boolean not null default false,
    opened_at timestamptz,
    page integer,
    zoom real,
    tool text,
    updated_at timestamptz not null
);
create index drive_file_state_opened on drive_file_state (opened_at desc) where opened_at is not null;
create index drive_file_state_starred on drive_file_state (path) where starred;
