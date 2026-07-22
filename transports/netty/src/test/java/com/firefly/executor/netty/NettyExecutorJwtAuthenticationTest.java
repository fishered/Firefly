package com.firefly.executor.netty;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.executor.InMemoryExecutorInstanceDirectory;
import com.firefly.executor.InMemoryExecutorRegistry;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.security.FireflyRole;
import com.firefly.security.JwtClient;
import com.firefly.security.JwtService;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyExecutorJwtAuthenticationTest {
    @Test
    void acceptsOnlyExecutorsWithinTheJwtScope() {
        JwtService jwt = new JwtService("01234567890123456789012345678901", "firefly",
                Duration.ofHours(1), Clock.systemUTC());
        String token = jwt.issue(new JwtClient(
                "billing", "client-secret", Set.of(FireflyRole.EXECUTOR), Set.of("billing-executor")
        ));
        java.util.function.BiPredicate<String, String> authenticator = (provided, executorName) ->
                jwt.verify(provided).allowsExecutor(executorName);

        assertEquals(NettyExecutorMessageType.REGISTERED,
                register("billing-executor", token, authenticator).type());
        NettyExecutorMessage rejected = register("orders-executor", token, authenticator);
        assertEquals(NettyExecutorMessageType.REGISTER_REJECTED, rejected.type());
        assertEquals("AUTHENTICATION_FAILED", rejected.payload().get("reasonCode"));
    }

    private NettyExecutorMessage register(
            String executorName, String token, java.util.function.BiPredicate<String, String> authenticator
    ) {
        NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();
        EmbeddedChannel channel = new EmbeddedChannel(new NettyExecutorGatewayHandler(
                new InMemoryExecutorRegistry(), new NettyExecutorConnectionRegistry(), codec, Clock.systemUTC(),
                new InMemorySchedulerCatalog(), true, "gateway-a", new InMemoryExecutionRepository(),
                (executionId, acknowledgedAt) -> { }, Runnable::run, authenticator,
                (executionId, timeout) -> { }, new SchedulerMetrics(),
                new InMemoryExecutorInstanceDirectory(), "", Duration.ofSeconds(30), Duration.ofSeconds(90),
                () -> true
        ));
        channel.writeInbound(codec.encode(new NettyExecutorMessage(
                "register", NettyExecutorMessageType.REGISTER_EXECUTOR,
                Map.of(
                        "executorName", executorName,
                        "instanceId", "instance-1",
                        "sessionId", "session-1",
                        "serviceName", "billing-service",
                        "protocolVersion", Integer.toString(NettyExecutorProtocol.CURRENT_VERSION),
                        "capabilities", NettyExecutorProtocol.encodeCapabilities(NettyExecutorProtocol.SERVER_CAPABILITIES),
                        "authToken", token
                )
        )));
        NettyExecutorMessage response = codec.decode(((String) channel.readOutbound()).trim());
        channel.finishAndReleaseAll();
        return response;
    }
}
