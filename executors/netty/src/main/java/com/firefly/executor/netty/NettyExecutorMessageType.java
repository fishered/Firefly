package com.firefly.executor.netty;

/**
 * Message types exchanged on the Firefly executor long connection.
 */
public enum NettyExecutorMessageType {
    REGISTER_EXECUTOR,
    HEARTBEAT,
    TRIGGER_JOB,
    ACK_JOB,
    REPORT_RESULT,
    UNREGISTER_EXECUTOR
}
