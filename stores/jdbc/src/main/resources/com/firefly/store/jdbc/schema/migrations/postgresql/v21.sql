create table if not exists firefly_tenant_capacity_sample (
    tenant_id varchar(128) not null,
    bucket_start timestamp not null,
    sample_count bigint not null,
    min_policy_version bigint not null,
    max_policy_version bigint not null,
    min_runtime_version varchar(32) not null,
    max_runtime_version varchar(32) not null,
    max_active_executions bigint not null,
    max_reserved_cpu_millis bigint not null,
    max_reserved_memory_bytes bigint not null,
    max_backlog bigint not null,
    max_trigger_rate bigint not null,
    saturated_concurrency_samples bigint not null,
    saturated_cpu_samples bigint not null,
    saturated_memory_samples bigint not null,
    saturated_backlog_samples bigint not null,
    saturated_trigger_rate_samples bigint not null,
    rejected_concurrency bigint not null,
    rejected_cpu bigint not null,
    rejected_memory bigint not null,
    rejected_backlog bigint not null,
    rejected_trigger_rate bigint not null,
    rejected_disabled bigint not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (tenant_id, bucket_start)
);

create table if not exists firefly_tenant_capacity_flush (
    flush_id varchar(512) primary key,
    bucket_start timestamp not null,
    created_at timestamp not null
);

create index if not exists idx_firefly_capacity_bucket_tenant
    on firefly_tenant_capacity_sample(bucket_start, tenant_id);
create index if not exists idx_firefly_capacity_flush_bucket
    on firefly_tenant_capacity_flush(bucket_start, flush_id);
