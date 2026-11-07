create table if not exists firefly_tenant_policy (
    tenant_id varchar(128) primary key,
    enabled boolean not null,
    weight integer not null,
    max_concurrent integer not null,
    max_cpu_millis bigint not null,
    max_memory_bytes bigint not null,
    max_backlog bigint not null,
    max_trigger_rate bigint not null,
    version bigint not null,
    updated_at timestamp with time zone not null
);
alter table firefly_dispatch_outbox add column if not exists tenant_id varchar(128) not null default 'default';
alter table firefly_dispatch_outbox add column if not exists queue_priority integer not null default 0;
create table if not exists firefly_tenant_usage (
    tenant_id varchar(128) primary key references firefly_tenant_policy(tenant_id),
    active_executions integer not null,
    reserved_cpu_millis bigint not null,
    reserved_memory_bytes bigint not null,
    backlog bigint not null,
    trigger_window_started_at timestamp with time zone not null,
    triggers_in_window bigint not null,
    version bigint not null
);
create table if not exists firefly_tenant_reservation (
    reservation_id varchar(256) primary key,
    tenant_id varchar(128) not null references firefly_tenant_policy(tenant_id),
    concurrent_executions integer not null,
    cpu_millis bigint not null,
    memory_bytes bigint not null,
    backlog bigint not null,
    triggers bigint not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null
);
create table if not exists firefly_dispatch_fairness_state (
    state_key varchar(256) primary key,
    virtual_finish bigint not null,
    updated_at timestamp with time zone not null
);
insert into firefly_tenant_policy values
('default', true, 1, 0, 0, 0, 0, 0, 1, timestamp with time zone '1970-01-01 00:00:00+00:00')
on conflict (tenant_id) do nothing;
insert into firefly_tenant_usage values
('default', 0, 0, 0, 0, timestamp with time zone '1970-01-01 00:00:00+00:00', 0, 0)
on conflict (tenant_id) do nothing;
insert into firefly_dispatch_fairness_state values
('__global__', 0, timestamp with time zone '1970-01-01 00:00:00+00:00')
on conflict (state_key) do nothing;
create index if not exists idx_firefly_tenant_reservation_expiry
    on firefly_tenant_reservation(expires_at, reservation_id);
create index if not exists idx_firefly_tenant_reservation_tenant_expiry
    on firefly_tenant_reservation(tenant_id, expires_at, reservation_id);
create index if not exists idx_firefly_outbox_tenant_claim
    on firefly_dispatch_outbox(dispatch_type, status, tenant_id, available_at, outbox_id);
