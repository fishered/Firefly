package com.firefly.server;

import com.firefly.cluster.NodeStatus;
import com.firefly.store.jdbc.JdbcNodeRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FireflyBootstrapRollbackTest {
    @Test
    void adminPortConflictRollsBackGatewayAndMarksNodeOffline() throws IOException {
        int adminPort = freePort();
        int gatewayPort = freePort();
        String database = "startup-rollback-" + UUID.randomUUID();
        String jdbcUrl = "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1";

        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", adminPort));
            ServerOptions options = ServerOptions.parse(new String[]{
                    "--firefly.node.name=startup-rollback-node",
                    "--firefly.node.roles=api,gateway,scheduler",
                    "--firefly.admin-http.port=" + adminPort,
                    "--firefly.executor.gateway.netty.port=" + gatewayPort,
                    "--firefly.store.type=jdbc",
                    "--firefly.jdbc.url=" + jdbcUrl,
                    "--firefly.jdbc.username=sa",
                    "--firefly.jdbc.dialect=h2",
                    "--firefly.jdbc.schema.mode=initialize-if-empty"
            }, Map.of());

            assertThrows(RuntimeException.class, () -> FireflyBootstrap.start(options));
        }

        try (ServerSocket reboundGateway = new ServerSocket()) {
            reboundGateway.bind(new InetSocketAddress("127.0.0.1", gatewayPort));
        }

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(jdbcUrl);
        dataSource.setUser("sa");
        assertEquals(NodeStatus.OFFLINE, new JdbcNodeRegistry(dataSource)
                .find("startup-rollback-node").orElseThrow().status());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
