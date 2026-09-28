create table if not exists firefly_backfill_operation (
 request_id varchar(128) primary key, job_id varchar(128) not null, root_execution_id varchar(256) not null,
 from_inclusive timestamp with time zone not null, to_inclusive timestamp with time zone not null,
 max_executions integer not null, batch_size integer not null, rate_limit_per_second integer not null,
 canary_percent integer not null, canary_executions integer not null, status varchar(16) not null,
 canary_active boolean not null, cursor integer not null, expanded integer not null,
 dispatched integer not null, failed integer not null, definition_snapshot clob not null,
 next_allowed_at timestamp with time zone not null, claim_owner varchar(128),
 claim_until timestamp with time zone, version bigint not null,
 created_at timestamp with time zone not null, updated_at timestamp with time zone not null
);
create index if not exists idx_firefly_backfill_runnable on firefly_backfill_operation(status, next_allowed_at, claim_until, created_at);
create table if not exists firefly_backfill_item (
 request_id varchar(128) not null, ordinal integer not null,
 fire_time timestamp with time zone not null, execution_id varchar(256) not null,
 status varchar(16) not null, error_message clob not null, updated_at timestamp with time zone not null,
 primary key (request_id, ordinal),
 foreign key (request_id) references firefly_backfill_operation(request_id) on delete cascade
);
create index if not exists idx_firefly_backfill_item_execution on firefly_backfill_item(execution_id);
