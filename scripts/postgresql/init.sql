-- Canonical PostgreSQL schema for a fresh Firefly installation.
-- Creates only Firefly-managed objects; the database, role, and grants are operator-owned.

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
    registered_at timestamp with time zone not null,
    last_heartbeat_at timestamp with time zone not null,
    status varchar(32) not null,
    metadata text not null
);

create table if not exists firefly_shard_lease (
    shard_id integer primary key,
    owner_node_id varchar(128) not null,
    lease_until timestamp with time zone not null,
    fencing_token bigint not null
);

create table if not exists firefly_cluster_metadata (
    metadata_key varchar(128) primary key,
    metadata_value text not null,
    updated_at timestamp with time zone not null
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
    metadata text not null,
    enabled boolean not null
);

create table if not exists firefly_job_group (
    group_id varchar(128) primary key,
    group_name varchar(256) not null,
    executor_name varchar(128) not null,
    metadata text not null,
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
    parameters text not null,
    shard_id integer not null default 0,
    dispatch_mode varchar(32) not null default 'UNICAST',
    routing_strategy varchar(32) not null default 'ROUND_ROBIN',
    completion_policy varchar(32) not null default 'ALL_SUCCESS',
    shard_count integer not null default 1,
    routing_key varchar(512) not null default '',
    retry_scope varchar(32) not null default 'FAILED_TARGETS_ONLY',
    enabled boolean not null,
    next_fire_time timestamp with time zone not null,
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
    scheduled_fire_time timestamp with time zone not null,
    dispatch_time timestamp with time zone not null,
    dispatch_mode varchar(32) not null,
    completion_policy varchar(32) not null,
    status varchar(32) not null,
    expected_targets integer not null,
    accepted_targets integer not null,
    owner_node_id varchar(128) not null,
    fencing_token bigint not null,
    timeout_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_firefly_execution_recent on firefly_execution (created_at desc, execution_id);

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
    acknowledged_at timestamp with time zone,
    completed_at timestamp with time zone,
    error_message text not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_firefly_execution_target_parent on firefly_execution_target (execution_id, target_execution_id);

create table if not exists firefly_dispatch_outbox (
    outbox_id varchar(256) primary key,
    execution_id varchar(256) not null unique,
    root_execution_id varchar(256) not null,
    run_attempt integer not null,
    job_id varchar(128) not null,
    scheduled_fire_time timestamp with time zone not null,
    dispatch_time timestamp with time zone not null,
    status varchar(32) not null,
    attempt integer not null,
    available_at timestamp with time zone not null,
    claim_owner varchar(128),
    claim_until timestamp with time zone,
    ack_deadline timestamp with time zone,
    owner_node_id varchar(128) not null,
    fencing_token bigint not null,
    dispatch_type varchar(16) not null,
    snapshot_payload text not null,
    last_error text not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_firefly_outbox_claim
    on firefly_dispatch_outbox (status, available_at, ack_deadline, claim_until);

create index if not exists idx_firefly_outbox_role_claim
    on firefly_dispatch_outbox (dispatch_type, status, available_at, ack_deadline, claim_until, outbox_id);

create table if not exists firefly_executor_instance_location (
    executor_name varchar(128) not null,
    instance_id varchar(256) not null,
    gateway_node_id varchar(128) not null,
    gateway_address varchar(512) not null,
    session_id varchar(256) not null,
    status varchar(32) not null,
    last_seen_at timestamp not null,
    lease_until timestamp not null,
    metadata text not null,
    primary key (executor_name, instance_id)
);

create index if not exists idx_firefly_executor_location_lease
    on firefly_executor_instance_location (executor_name, status, lease_until, gateway_node_id);

create table if not exists firefly_audit_log (
    audit_id varchar(64) primary key,
    occurred_at timestamp not null,
    actor varchar(256) not null,
    role_name varchar(64) not null,
    action_name varchar(128) not null,
    resource_type varchar(128) not null,
    resource_id varchar(256) not null,
    outcome varchar(64) not null,
    before_payload text not null,
    after_payload text not null,
    detail text not null
);

create index if not exists idx_firefly_audit_time on firefly_audit_log (occurred_at, audit_id);

create table if not exists firefly_job_history (
    history_id varchar(64) primary key,
    job_id varchar(128) not null,
    job_version bigint not null,
    action_name varchar(64) not null,
    actor varchar(256) not null,
    before_payload text not null,
    after_payload text not null,
    occurred_at timestamp not null
);

create index if not exists idx_firefly_job_history_job
    on firefly_job_history (job_id, occurred_at, history_id);

create table if not exists firefly_user (
    username varchar(128) primary key,
    password_hash varchar(512) not null,
    roles varchar(256) not null,
    enabled boolean not null,
    password_change_required boolean not null default false,
    version bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_firefly_user_enabled on firefly_user (enabled, username);

insert into firefly_user
    (username, password_hash, roles, enabled, password_change_required, version, created_at, updated_at)
select 'admin',
       'pbkdf2-sha256$210000$cdNnTGyvKtyrY2J5VniRJw$fugVWfUlpN9f84Rkjagj5aBkaBGyWwJuy68TBfJCAe4',
       'ADMIN', true, true, 0, current_timestamp, current_timestamp
where not exists (select 1 from firefly_user where username = 'admin');

create table if not exists firefly_integration_key (
    key_id varchar(64) primary key,
    key_hash varchar(512) not null,
    version bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

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

insert into firefly_schema_version (version, installed_at)
select 9, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 9);

insert into firefly_schema_version (version, installed_at)
select 10, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 10);

insert into firefly_schema_version (version, installed_at)
select 11, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 11);

insert into firefly_schema_version (version, installed_at)
select 12, current_timestamp
where not exists (select 1 from firefly_schema_version where version = 12);

-- Additive feature schema (v13-v19). These tables intentionally retain the
-- v12 core compatibility marker while making a fresh database service-ready.
create table if not exists firefly_calendar (
    calendar_id varchar(128) not null,
    calendar_version bigint not null,
    zone_id varchar(128) not null,
    working_days varchar(128) not null,
    holidays text not null,
    extra_working_days text not null,
    primary key (calendar_id, calendar_version)
);

create table if not exists firefly_trigger_inbox (
    event_id varchar(256) not null,
    event_type varchar(128) not null,
    idempotency_key varchar(256) primary key,
    job_id varchar(128) not null default '',
    payload text not null,
    status varchar(32) not null,
    failure_reason varchar(4096) not null default '',
    received_at timestamp not null,
    processed_at timestamp null
);

create table if not exists firefly_trigger_nonce (
    nonce varchar(256) primary key,
    expires_at timestamp not null,
    created_at timestamp not null
);

create table if not exists firefly_batch_execution (
    root_execution_id varchar(256) primary key,
    job_id varchar(128) not null,
    total_shards integer not null,
    completed_shards integer not null,
    failed_shards integer not null,
    retried_shards integer not null,
    input_records bigint not null,
    output_records bigint not null,
    progress_percent integer not null,
    sla_status varchar(32) not null,
    started_at timestamp not null,
    deadline timestamp not null,
    updated_at timestamp not null,
    fencing_token bigint not null default 0
);

create table if not exists firefly_batch_shard (
    root_execution_id varchar(256) not null,
    shard_index integer not null,
    attempt integer not null,
    status varchar(32) not null,
    input_records bigint not null,
    output_records bigint not null,
    checkpoint_id varchar(256) not null,
    checksum varchar(256) not null,
    error_message varchar(4096) not null,
    completed_at timestamp not null,
    primary key (root_execution_id, shard_index, attempt)
);

create table if not exists firefly_batch_checkpoint (
    checkpoint_id varchar(256) primary key,
    root_execution_id varchar(256) not null,
    shard_index integer not null,
    attempt integer not null,
    location varchar(1024) not null,
    checksum varchar(256) not null,
    created_at timestamp not null
);

create table if not exists firefly_calendar_metadata (
    calendar_id varchar(128) not null,
    calendar_version bigint not null,
    jurisdiction varchar(64) not null default 'CUSTOM',
    published_at timestamp with time zone null,
    published_by varchar(128) null,
    primary key (calendar_id, calendar_version)
);

create table if not exists firefly_calendar_date_rule (
    calendar_id varchar(128) not null,
    calendar_version bigint not null,
    rule_date date not null,
    rule_kind varchar(16) not null,
    rule_name varchar(256) not null,
    source varchar(16) not null,
    locked boolean not null default false,
    import_id varchar(128) null,
    reason varchar(1024) not null,
    primary key (calendar_id, calendar_version, rule_date),
    foreign key (calendar_id, calendar_version) references firefly_calendar(calendar_id, calendar_version)
);

create table if not exists firefly_calendar_import (
    import_id varchar(128) primary key,
    calendar_id varchar(128) not null,
    target_version bigint not null,
    provider_id varchar(128) not null,
    provider_version varchar(128) not null,
    jurisdiction varchar(64) not null,
    zone_id varchar(128) not null,
    range_start date not null,
    range_end date not null,
    source_uri varchar(1024) not null,
    source_checksum varchar(128) not null,
    status varchar(32) not null,
    conflict_count integer not null default 0,
    imported_at timestamp with time zone not null,
    imported_by varchar(128) not null,
    published_at timestamp with time zone null
);

create table if not exists firefly_dependency_wait (
    job_id varchar(128) not null,
    business_time timestamp with time zone not null,
    wait_attempts integer not null,
    next_check_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (job_id, business_time)
);

create table if not exists firefly_condition_state (
    job_id varchar(128) not null,
    business_time timestamp with time zone not null,
    status varchar(16) not null,
    reason varchar(1024) not null,
    updated_at timestamp with time zone not null,
    primary key (job_id, business_time)
);

create table if not exists firefly_dependency_gate (
    gate_id varchar(256) primary key,
    job_id varchar(128) not null,
    business_time timestamp with time zone not null,
    next_check_at timestamp with time zone not null,
    deadline_at timestamp with time zone not null,
    wait_attempts integer not null,
    status varchar(16) not null,
    reason varchar(1024) not null,
    updated_at timestamp with time zone not null
);

create table if not exists firefly_event_aggregation_lock (
    job_id varchar(128) not null,
    aggregation_key varchar(256) not null,
    primary key (job_id, aggregation_key)
);

create table if not exists firefly_event_aggregate (
    aggregate_id varchar(64) primary key,
    job_id varchar(128) not null,
    aggregation_key varchar(256) not null,
    latest_payload text not null,
    event_count integer not null,
    first_received_at timestamp with time zone not null,
    ready_at timestamp with time zone not null,
    deadline_at timestamp with time zone not null,
    status varchar(16) not null,
    claim_owner varchar(128),
    claim_until timestamp with time zone,
    updated_at timestamp with time zone not null
);

create table if not exists firefly_event_aggregate_member (
    idempotency_key varchar(256) primary key,
    aggregate_id varchar(64) not null
);

create table if not exists firefly_backfill_operation (
    request_id varchar(128) primary key,
    job_id varchar(128) not null,
    root_execution_id varchar(256) not null,
    from_inclusive timestamp with time zone not null,
    to_inclusive timestamp with time zone not null,
    max_executions integer not null,
    batch_size integer not null,
    rate_limit_per_second integer not null,
    canary_percent integer not null,
    canary_executions integer not null,
    status varchar(16) not null,
    canary_active boolean not null,
    cursor_position integer not null,
    expanded integer not null,
    dispatched integer not null,
    failed integer not null,
    definition_snapshot text not null,
    next_allowed_at timestamp with time zone not null,
    claim_owner varchar(128),
    claim_until timestamp with time zone,
    version bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table if not exists firefly_backfill_item (
    request_id varchar(128) not null references firefly_backfill_operation(request_id) on delete cascade,
    ordinal integer not null,
    fire_time timestamp with time zone not null,
    execution_id varchar(256) not null,
    status varchar(16) not null,
    error_message text not null,
    updated_at timestamp with time zone not null,
    primary key (request_id, ordinal)
);

create index if not exists idx_firefly_calendar_rule_date on firefly_calendar_date_rule(calendar_id, calendar_version, rule_date);
create index if not exists idx_firefly_calendar_import_status on firefly_calendar_import(calendar_id, status, imported_at);
create index if not exists idx_firefly_dependency_wait_next on firefly_dependency_wait(next_check_at, job_id);
create index if not exists idx_firefly_dependency_gate_due on firefly_dependency_gate(status, next_check_at, job_id);
create index if not exists idx_firefly_event_aggregate_due on firefly_event_aggregate(status, ready_at, deadline_at, claim_until, aggregate_id);
create index if not exists idx_firefly_event_aggregate_member on firefly_event_aggregate_member(aggregate_id, idempotency_key);
create index if not exists idx_firefly_backfill_runnable on firefly_backfill_operation(status, next_allowed_at, claim_until, created_at);
create index if not exists idx_firefly_backfill_item_execution on firefly_backfill_item(execution_id);
create index if not exists idx_firefly_trigger_failed on firefly_trigger_inbox(status, received_at, idempotency_key);
create index if not exists idx_firefly_trigger_nonce_expiry on firefly_trigger_nonce(expires_at, nonce);
create index if not exists idx_firefly_execution_job_page on firefly_execution(job_id, created_at desc, execution_id desc);
create index if not exists idx_firefly_execution_status_page on firefly_execution(status, created_at desc, execution_id desc);
create index if not exists idx_firefly_audit_filter_page on firefly_audit_log(resource_type, outcome, occurred_at desc, audit_id desc);
create index if not exists idx_firefly_audit_actor_page on firefly_audit_log(actor, occurred_at desc, audit_id desc);
create index if not exists idx_firefly_audit_action_page on firefly_audit_log(action_name, occurred_at desc, audit_id desc);
create index if not exists idx_firefly_audit_outcome_page on firefly_audit_log(outcome, occurred_at desc, audit_id desc);
create index if not exists idx_firefly_job_history_filter_page on firefly_job_history(job_id, action_name, occurred_at desc, history_id desc);
create index if not exists idx_firefly_backfill_page on firefly_backfill_operation(job_id, status, created_at desc, request_id desc);
create index if not exists idx_firefly_backfill_status_page on firefly_backfill_operation(status, created_at desc, request_id desc);
create index if not exists idx_firefly_outbox_dead_page on firefly_dispatch_outbox(status, job_id, available_at desc, outbox_id desc);
create index if not exists idx_firefly_outbox_dead_type_page on firefly_dispatch_outbox(status, dispatch_type, available_at desc, outbox_id desc);
