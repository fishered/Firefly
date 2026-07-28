alter table firefly_user
    add column if not exists password_change_required boolean not null default false;

insert into firefly_user
    (username, password_hash, roles, enabled, password_change_required, version, created_at, updated_at)
select 'admin',
       'pbkdf2-sha256$210000$cdNnTGyvKtyrY2J5VniRJw$fugVWfUlpN9f84Rkjagj5aBkaBGyWwJuy68TBfJCAe4',
       'ADMIN', true, true, 0, current_timestamp, current_timestamp
where not exists (select 1 from firefly_user where username = 'admin');

update firefly_user
set password_change_required = true
where username = 'admin'
  and password_hash = 'pbkdf2-sha256$210000$cdNnTGyvKtyrY2J5VniRJw$fugVWfUlpN9f84Rkjagj5aBkaBGyWwJuy68TBfJCAe4';

insert into firefly_schema_version (version, installed_at)
select 12, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 12);
