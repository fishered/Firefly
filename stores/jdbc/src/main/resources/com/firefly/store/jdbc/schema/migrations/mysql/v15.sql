create table if not exists firefly_dependency_wait (
    job_id varchar(128) not null,
    business_time timestamp not null,
    wait_attempts int not null,
    next_check_at timestamp not null,
    updated_at timestamp not null,
    primary key (job_id, business_time)
);
create table if not exists firefly_condition_state (job_id varchar(128) not null, business_time timestamp not null, status varchar(16) not null, reason varchar(1024) not null, updated_at timestamp not null, primary key(job_id,business_time));
