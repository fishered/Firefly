create table if not exists firefly_dependency_gate (
 gate_id varchar(256) primary key, job_id varchar(128) not null, business_time timestamp not null,
 next_check_at timestamp not null, deadline_at timestamp not null, wait_attempts int not null,
 status varchar(16) not null, reason varchar(1024) not null, updated_at timestamp not null,
 index idx_firefly_dependency_gate_due(status,next_check_at,job_id)
);
