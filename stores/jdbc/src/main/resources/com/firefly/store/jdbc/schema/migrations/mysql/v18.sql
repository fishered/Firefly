create table if not exists firefly_backfill_operation (
 request_id varchar(128) primary key, job_id varchar(128) not null, root_execution_id varchar(256) not null,
 from_inclusive timestamp(6) not null, to_inclusive timestamp(6) not null,
 max_executions int not null, batch_size int not null, rate_limit_per_second int not null,
 canary_percent int not null, canary_executions int not null, status varchar(16) not null,
 canary_active boolean not null, cursor int not null, expanded int not null,
 dispatched int not null, failed int not null, definition_snapshot longtext not null,
 next_allowed_at timestamp(6) not null, claim_owner varchar(128), claim_until timestamp(6),
 version bigint not null, created_at timestamp(6) not null, updated_at timestamp(6) not null,
 index idx_firefly_backfill_runnable(status, next_allowed_at, claim_until, created_at)
);
create table if not exists firefly_backfill_item (
 request_id varchar(128) not null, ordinal int not null, fire_time timestamp(6) not null,
 execution_id varchar(256) not null, status varchar(16) not null,
 error_message longtext not null, updated_at timestamp(6) not null,
 primary key (request_id, ordinal),
 constraint fk_firefly_backfill_item_operation foreign key (request_id)
   references firefly_backfill_operation(request_id) on delete cascade,
 index idx_firefly_backfill_item_execution(execution_id)
);
