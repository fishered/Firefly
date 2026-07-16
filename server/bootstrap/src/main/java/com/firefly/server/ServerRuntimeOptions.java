package com.firefly.server;

import com.firefly.executor.netty.NettyExecutorGatewayOptions;
import com.firefly.store.jdbc.JdbcClockOptions;

import java.util.Objects;

/** Groups operational tuning that is independent from node identity and storage credentials. */
public record ServerRuntimeOptions(
        DispatchOutboxOptions dispatchOutbox,
        ExecutionMaintenanceOptions executionMaintenance,
        JdbcClockOptions jdbcClock,
        NettyExecutorGatewayOptions nettyGateway,
        AdminAuthorizationOptions adminAuthorization
) {
    public ServerRuntimeOptions(
            DispatchOutboxOptions dispatchOutbox,
            ExecutionMaintenanceOptions executionMaintenance,
            JdbcClockOptions jdbcClock,
            NettyExecutorGatewayOptions nettyGateway
    ) {
        this(dispatchOutbox, executionMaintenance, jdbcClock, nettyGateway,
                AdminAuthorizationOptions.defaults());
    }

    public ServerRuntimeOptions {
        Objects.requireNonNull(dispatchOutbox, "dispatchOutbox");
        Objects.requireNonNull(executionMaintenance, "executionMaintenance");
        Objects.requireNonNull(jdbcClock, "jdbcClock");
        Objects.requireNonNull(nettyGateway, "nettyGateway");
        Objects.requireNonNull(adminAuthorization, "adminAuthorization");
    }

    public static ServerRuntimeOptions defaults() {
        return new ServerRuntimeOptions(
                DispatchOutboxOptions.defaults(),
                ExecutionMaintenanceOptions.defaults(),
                JdbcClockOptions.defaults(),
                NettyExecutorGatewayOptions.defaults(),
                AdminAuthorizationOptions.defaults()
        );
    }
}
