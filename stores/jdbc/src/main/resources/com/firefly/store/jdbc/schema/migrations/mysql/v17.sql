create table if not exists firefly_event_aggregation_lock (
 job_id varchar(128) not null, aggregation_key varchar(256) not null,
 primary key (job_id, aggregation_key)
);
create table if not exists firefly_event_aggregate (
 aggregate_id varchar(64) primary key, job_id varchar(128) not null, aggregation_key varchar(256) not null,
 latest_payload longtext not null, event_count int not null,
 first_received_at timestamp(6) not null, ready_at timestamp(6) not null,
 deadline_at timestamp(6) not null, status varchar(16) not null,
 claim_owner varchar(128), claim_until timestamp(6), updated_at timestamp(6) not null,
 index idx_firefly_event_aggregate_due(status, ready_at, deadline_at, claim_until, aggregate_id)
);
create table if not exists firefly_event_aggregate_member (
 idempotency_key varchar(256) primary key, aggregate_id varchar(64) not null,
 index idx_firefly_event_aggregate_member(aggregate_id, idempotency_key)
);
