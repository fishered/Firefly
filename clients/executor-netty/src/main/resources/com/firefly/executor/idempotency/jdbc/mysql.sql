create table if not exists firefly_executor_idempotency (
    idempotency_key varchar(512) primary key,
    status varchar(32) not null,
    claim_token varchar(64) not null,
    claim_until timestamp(6) not null,
    attempt bigint not null,
    error_message varchar(4000) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    completed_at timestamp(6),
    index idx_firefly_executor_idempotency_cleanup (status, updated_at)
);
