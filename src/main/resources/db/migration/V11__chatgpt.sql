-- The ChatGPT account the model runs on (capture). The refresh token is encrypted like mail's.
create table chatgpt_accounts (
    email           text primary key,
    account_id      text        not null,
    plan            text,
    refresh_token   bytea       not null,
    connected_at    timestamptz not null default now(),
    -- Set when the sign-in stopped working (e.g. the token was revoked); cleared by reconnecting.
    last_error      text
);
