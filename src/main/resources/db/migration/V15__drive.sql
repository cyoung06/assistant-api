-- Where each project's folder is in the drive, so renaming a project moves its folder.
create table project_folders (
    project_id uuid primary key references projects (id) on delete cascade,
    path text not null
);

-- Course files copied from PLMS into the drive, one row per PLMS file: where it went, and the
-- version copied (PLMS's timemodified), so a changed file is copied again beside the old one.
create table plms_files (
    key text primary key,
    path text not null,
    modified bigint not null,
    size bigint not null,
    copied_at timestamptz not null
);
