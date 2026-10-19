-- Mail into Triage: each new mail in the watched folders becomes an input (once, by Message-ID),
-- and the model's verdict on it is kept with its reason.
alter table triage_inputs add column sender text;
-- act · maybe · nothing (mail only; shares are always read for suggestions).
alter table triage_inputs add column verdict text;
alter table triage_inputs add column reason text;
create unique index triage_inputs_mail on triage_inputs (link) where source = 'mail';

-- How far each mail folder has been read: the highest UID seen, valid while UIDVALIDITY holds.
create table mail_folders (
    folder        text primary key,
    uid_validity  bigint not null,
    last_uid      bigint not null
);

-- Small settings edited in the app (the triage profile).
create table settings (
    key   text primary key,
    value text not null
);
