create table if not exists firefly_node (
    node_id varchar(128) primary key,
    roles varchar(512) not null,
    registered_at timestamp(6) not null,
    last_heartbeat_at timestamp(6) not null,
    status varchar(32) not null,
    metadata text not null
);

create table if not exists firefly_shard_lease (
    shard_id integer primary key,
    owner_node_id varchar(128) not null,
    lease_until timestamp(6) not null,
    fencing_token bigint not null
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
    enabled boolean not null,
    next_fire_time timestamp(6) not null,
    version bigint not null,
    index idx_firefly_job_due (enabled, next_fire_time, job_id)
);
