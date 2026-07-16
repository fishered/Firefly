create table if not exists firefly_schema_version (
    version integer primary key,
    installed_at timestamp not null
);

insert into firefly_schema_version (version, installed_at)
select 1, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 1);

create table if not exists firefly_node (
    node_id varchar(128) primary key,
    roles varchar(512) not null,
    registered_at timestamp not null,
    last_heartbeat_at timestamp not null,
    status varchar(32) not null,
    metadata varchar(2048) not null
);

create table if not exists firefly_shard_lease (
    shard_id integer primary key,
    owner_node_id varchar(128) not null,
    lease_until timestamp not null,
    fencing_token bigint not null
);

create table if not exists firefly_cluster_metadata (
    metadata_key varchar(128) primary key,
    metadata_value varchar(2048) not null,
    updated_at timestamp not null
);

insert into firefly_cluster_metadata (metadata_key, metadata_value, updated_at)
select 'scheduler.shard-count', '32', current_timestamp
where not exists (select 1 from firefly_cluster_metadata where metadata_key='scheduler.shard-count');

insert into firefly_cluster_metadata (metadata_key, metadata_value, updated_at)
select 'jobs.revision', '0', current_timestamp
where not exists (select 1 from firefly_cluster_metadata where metadata_key='jobs.revision');

create table if not exists firefly_executor (
    executor_name varchar(128) primary key,
    description varchar(1024) not null,
    protocols varchar(256) not null,
    metadata varchar(4096) not null,
    enabled boolean not null
);

create table if not exists firefly_job_group (
    group_id varchar(128) primary key,
    group_name varchar(256) not null,
    executor_name varchar(128) not null,
    metadata varchar(4096) not null,
    enabled boolean not null
);

create table if not exists firefly_job (
    job_id varchar(128) primary key,
    group_id varchar(128) not null,
    job_name varchar(256) not null,
    handler_name varchar(256) not null,
    schedule_type varchar(32) not null,
    schedule_value varchar(512) not null,
    zone_id varchar(128) not null,
    misfire_policy varchar(32) not null,
    misfire_grace varchar(64) not null,
    concurrency_policy varchar(32) not null,
    max_catch_up_count integer not null,
    timeout_value varchar(64) not null,
    parameters varchar(4096) not null,
    shard_id integer not null default 0,
    dispatch_mode varchar(32) not null default 'UNICAST',
    routing_strategy varchar(32) not null default 'ROUND_ROBIN',
    completion_policy varchar(32) not null default 'ALL_SUCCESS',
    shard_count integer not null default 1,
    routing_key varchar(512) not null default '',
    enabled boolean not null,
    next_fire_time timestamp not null,
    version bigint not null
);

create index if not exists idx_firefly_job_due
    on firefly_job (enabled, next_fire_time, job_id);

create index if not exists idx_firefly_job_shard_due
    on firefly_job (shard_id, enabled, next_fire_time, job_id);

create table if not exists firefly_execution (
    execution_id varchar(256) primary key,
    root_execution_id varchar(256) not null,
    run_attempt integer not null,
    retry_scheduled boolean not null,
    job_id varchar(128) not null,
    scheduled_fire_time timestamp not null,
    dispatch_time timestamp not null,
    dispatch_mode varchar(32) not null,
    completion_policy varchar(32) not null,
    status varchar(32) not null,
    expected_targets integer not null,
    accepted_targets integer not null,
    owner_node_id varchar(128) not null,
    fencing_token bigint not null,
    timeout_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index if not exists idx_firefly_execution_recent on firefly_execution (created_at, execution_id);

create index if not exists idx_firefly_execution_timeout
    on firefly_execution (status, timeout_at, execution_id);

create table if not exists firefly_execution_target (
    target_execution_id varchar(384) primary key,
    execution_id varchar(256) not null,
    instance_id varchar(256) not null,
    gateway_node_id varchar(128) not null,
    shard_index integer,
    status varchar(32) not null,
    attempt integer not null,
    acknowledged_at timestamp,
    completed_at timestamp,
    error_message varchar(4096) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index if not exists idx_firefly_execution_target_parent on firefly_execution_target (execution_id, target_execution_id);

create table if not exists firefly_dispatch_outbox (
    outbox_id varchar(256) primary key,
    execution_id varchar(256) not null unique,
    root_execution_id varchar(256) not null,
    run_attempt integer not null,
    job_id varchar(128) not null,
    scheduled_fire_time timestamp not null,
    dispatch_time timestamp not null,
    status varchar(32) not null,
    attempt integer not null,
    available_at timestamp not null,
    claim_owner varchar(128),
    claim_until timestamp,
    ack_deadline timestamp,
    owner_node_id varchar(128) not null,
    fencing_token bigint not null,
    dispatch_type varchar(16) not null,
    snapshot_payload clob not null,
    last_error varchar(4096) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index if not exists idx_firefly_outbox_claim
    on firefly_dispatch_outbox (status, available_at, ack_deadline, claim_until);

create index if not exists idx_firefly_outbox_role_claim
    on firefly_dispatch_outbox (dispatch_type, status, available_at, ack_deadline, claim_until, outbox_id);

insert into firefly_schema_version (version, installed_at)
select 2, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 2);

insert into firefly_schema_version (version, installed_at)
select 3, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 3);

insert into firefly_schema_version (version, installed_at)
select 4, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 4);

insert into firefly_schema_version (version, installed_at)
select 5, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 5);

insert into firefly_schema_version (version, installed_at)
select 6, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 6);

insert into firefly_schema_version (version, installed_at)
select 7, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 7);

insert into firefly_schema_version (version, installed_at)
select 8, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 8);
