-- Secret tokens for the iCal export feed. Only one is active; rotating revokes the old one.
create table feed_tokens (
    token      text primary key,
    created_at timestamptz not null default now(),
    revoked_at timestamptz
);

-- Items imported from subscribed iCal feeds.
alter table items drop constraint items_source_type_check;
alter table items add constraint items_source_type_check
    check (source_type in ('manual', 'plms', 'email', 'upload', 'ical'));
