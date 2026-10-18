-- Personal tokens for the few endpoints reachable from the internet (the Outlook add-in). Only a
-- SHA-256 of each token is kept.
create table public_tokens (
    id         uuid primary key,
    name       text        not null,
    token_hash text        not null unique,
    created_at timestamptz not null default now(),
    last_used  timestamptz,
    revoked_at timestamptz
);
