-- Mail accounts connected with OAuth. The refresh token is encrypted with a key from the
-- deployment's secret; mail itself stays on the mail server.
create table mail_accounts (
    email           text primary key,
    refresh_token   bytea       not null,
    connected_at    timestamptz not null default now(),
    -- Set when the sign-in stopped working (e.g. the token was revoked); cleared by reconnecting.
    last_error      text
);
