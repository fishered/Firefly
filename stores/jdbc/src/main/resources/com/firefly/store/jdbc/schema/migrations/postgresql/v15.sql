create table if not exists firefly_dependency_wait (
    job_id varchar(128) not null,
    business_time timestamp with time zone not null,
    wait_attempts integer not null,
    next_check_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (job_id, business_time)
);
create index if not exists idx_firefly_dependency_wait_next on firefly_dependency_wait(next_check_at, job_id);
create table if not exists firefly_condition_state (job_id varchar(128) not null, business_time timestamp with time zone not null, status varchar(16) not null, reason varchar(1024) not null, updated_at timestamp with time zone not null, primary key(job_id,business_time));
