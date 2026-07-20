create table if not exists firefly_executor_idempotency (
    idempotency_key varchar(512) primary key,
    status varchar(32) not null,
    claim_token varchar(64) not null,
    claim_until timestamp with time zone not null,
    attempt bigint not null,
    error_message varchar(4000) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

create index if not exists idx_firefly_executor_idempotency_cleanup
    on firefly_executor_idempotency (status, updated_at);
