-- Triage, as in Linear: everything that comes in (shared screenshots and text, captures, mail) is
-- kept as an input, read by the model in the background, and turned into suggestions the user
-- accepts (a task or event is made) or declines. Nothing is made without accepting.
create table triage_inputs (
    id          uuid primary key,
    -- Where it came from: share, capture, addin, mail, …
    source      text        not null,
    -- What was shared or sent along (a mail's subject, sender and text, a note, …).
    text        text        not null default '',
    -- A short label for the list (e.g. the mail subject or "Screenshot").
    title       text        not null default '',
    -- A link back to where it came from (e.g. mid:<Message-ID>), when there is one.
    link        text,
    -- pending (waiting for the model) · done · failed (after a few tries).
    status      text        not null default 'pending' check (status in ('pending', 'done', 'failed')),
    attempts    int         not null default 0,
    error       text,
    created_at  timestamptz not null default now(),
    analyzed_at timestamptz,
    -- When the next try may run (after a failure, or while the model isn't connected).
    retry_at    timestamptz not null default now()
);
create index triage_inputs_status on triage_inputs (status, retry_at);

create table triage_items (
    id          uuid primary key,
    input_id    uuid        not null references triage_inputs (id) on delete cascade,
    position    int         not null,
    -- The model's proposal (task or event), as the capture API returns it; edited before accepting.
    proposal    jsonb       not null,
    -- Number of an open task this seems to repeat, per the model.
    same_as     bigint,
    -- pending · accepted · declined
    status      text        not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
    -- What accepting made: a link to the task or event.
    result      text,
    decided_at  timestamptz
);
create index triage_items_input on triage_items (input_id);

-- Shared screenshots wait with their input until a suggestion from it is accepted.
alter table attachments add column triage_input_id uuid references triage_inputs (id) on delete set null;
