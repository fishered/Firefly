package com.firefly.server;

import com.firefly.executor.netty.NettyExecutorGatewayOptions;
import com.firefly.store.jdbc.JdbcClockOptions;
import com.firefly.engine.SchedulerEngineOptions;

import java.util.Objects;

/** Groups operational tuning that is independent from node identity and storage credentials. */
public record ServerRuntimeOptions(
        DispatchOutboxOptions dispatchOutbox,
        ExecutionMaintenanceOptions executionMaintenance,
        JdbcClockOptions jdbcClock,
        NettyExecutorGatewayOptions nettyGateway,
        AdminAuthorizationOptions adminAuthorization,
        SchedulerEngineOptions schedulerEngine,
        JwtSecurityOptions jwtSecurity,
        AdminSecurityOptions adminSecurity
) {
    public ServerRuntimeOptions(
            DispatchOutboxOptions dispatchOutbox,
            ExecutionMaintenanceOptions executionMaintenance,
            JdbcClockOptions jdbcClock,
            NettyExecutorGatewayOptions nettyGateway
    ) {
        this(dispatchOutbox, executionMaintenance, jdbcClock, nettyGateway,
                AdminAuthorizationOptions.defaults(), SchedulerEngineOptions.defaults(), JwtSecurityOptions.disabled(),
                AdminSecurityOptions.disabled());
    }

    public ServerRuntimeOptions(
            DispatchOutboxOptions dispatchOutbox,
            ExecutionMaintenanceOptions executionMaintenance,
            JdbcClockOptions jdbcClock,
            NettyExecutorGatewayOptions nettyGateway,
            AdminAuthorizationOptions adminAuthorization
    ) {
        this(dispatchOutbox, executionMaintenance, jdbcClock, nettyGateway,
                adminAuthorization, SchedulerEngineOptions.defaults(), JwtSecurityOptions.disabled(),
                AdminSecurityOptions.disabled());
    }

    public ServerRuntimeOptions(
            DispatchOutboxOptions dispatchOutbox,
            ExecutionMaintenanceOptions executionMaintenance,
            JdbcClockOptions jdbcClock,
            NettyExecutorGatewayOptions nettyGateway,
            AdminAuthorizationOptions adminAuthorization,
            SchedulerEngineOptions schedulerEngine
    ) {
        this(dispatchOutbox, executionMaintenance, jdbcClock, nettyGateway,
                adminAuthorization, schedulerEngine, JwtSecurityOptions.disabled(), AdminSecurityOptions.disabled());
    }

    public ServerRuntimeOptions {
        Objects.requireNonNull(dispatchOutbox, "dispatchOutbox");
        Objects.requireNonNull(executionMaintenance, "executionMaintenance");
        Objects.requireNonNull(jdbcClock, "jdbcClock");
        Objects.requireNonNull(nettyGateway, "nettyGateway");
        Objects.requireNonNull(adminAuthorization, "adminAuthorization");
        Objects.requireNonNull(schedulerEngine, "schedulerEngine");
        Objects.requireNonNull(jwtSecurity, "jwtSecurity");
        Objects.requireNonNull(adminSecurity, "adminSecurity");
    }

    public static ServerRuntimeOptions defaults() {
        return new ServerRuntimeOptions(
                DispatchOutboxOptions.defaults(),
                ExecutionMaintenanceOptions.defaults(),
                JdbcClockOptions.defaults(),
                NettyExecutorGatewayOptions.defaults(),
                AdminAuthorizationOptions.defaults(),
                SchedulerEngineOptions.defaults(),
                JwtSecurityOptions.disabled(),
                AdminSecurityOptions.disabled()
        );
    }
}
