create table if not exists firefly_dependency_gate (
 gate_id varchar(256) primary key, job_id varchar(128) not null, business_time timestamp with time zone not null,
 next_check_at timestamp with time zone not null, deadline_at timestamp with time zone not null,
 wait_attempts integer not null, status varchar(16) not null, reason varchar(1024) not null,
 updated_at timestamp with time zone not null
);
create index if not exists idx_firefly_dependency_gate_due on firefly_dependency_gate(status,next_check_at,job_id);
