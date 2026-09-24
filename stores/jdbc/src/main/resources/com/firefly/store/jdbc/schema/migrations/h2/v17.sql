create table if not exists firefly_event_aggregation_lock (
 job_id varchar(128) not null, aggregation_key varchar(256) not null,
 primary key (job_id, aggregation_key)
);
create table if not exists firefly_event_aggregate (
 aggregate_id varchar(64) primary key, job_id varchar(128) not null, aggregation_key varchar(256) not null,
 latest_payload clob not null, event_count integer not null,
 first_received_at timestamp with time zone not null, ready_at timestamp with time zone not null,
 deadline_at timestamp with time zone not null, status varchar(16) not null,
 claim_owner varchar(128), claim_until timestamp with time zone, updated_at timestamp with time zone not null
);
create index if not exists idx_firefly_event_aggregate_due on firefly_event_aggregate(status, ready_at, deadline_at, claim_until, aggregate_id);
create table if not exists firefly_event_aggregate_member (
 idempotency_key varchar(256) primary key, aggregate_id varchar(64) not null
);
create index if not exists idx_firefly_event_aggregate_member on firefly_event_aggregate_member(aggregate_id, idempotency_key);
