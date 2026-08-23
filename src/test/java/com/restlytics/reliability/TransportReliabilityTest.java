package com.restlytics.reliability;

import com.restlytics.HttpTransport;
import com.restlytics.TransportDiagnostics;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransportReliabilityTest {

    @Test
    void sendIsNonBlockingBoundedObservableAndFlushable() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            try {
                gate.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(202, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        HttpTransport transport = new HttpTransport(
                "http://127.0.0.1:" + server.getAddress().getPort(), "rl_test", 500, 4, null);
        try {
            long started = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                transport.send("{}");
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 250);
            TransportDiagnostics snapshot = transport.diagnostics();
            assertTrue(snapshot.acceptedBatches() <= 5);
            assertTrue(snapshot.droppedBatches() >= 5);
            assertEquals(4, snapshot.queueCapacity());

            gate.countDown();
            assertTrue(transport.flush(2000));
            assertEquals(snapshot.acceptedBatches(), transport.diagnostics().deliveredBatches());
            transport.close();
            assertDoesNotThrow(() -> transport.send("{}"));
            assertEquals(snapshot.droppedBatches() + 1, transport.diagnostics().droppedBatches());
        } finally {
            gate.countDown();
            transport.close();
            server.stop(0);
        }
    }

    @Test
    void timeoutIsCountedSwallowedAndNeverRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            attempts.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        HttpTransport transport = new HttpTransport(
                "http://127.0.0.1:" + server.getAddress().getPort(), "rl_test", 20);
        try {
            assertDoesNotThrow(() -> transport.send("{}"));
            assertTrue(transport.flush(1000));
            // A 20ms deadline may expire during connect before HttpServer dispatches
            // the handler, but it must never result in more than one attempt.
            assertTrue(attempts.get() <= 1);
            assertEquals(1, transport.diagnostics().failedBatches());
            assertEquals(0, transport.diagnostics().deliveredBatches());
        } finally {
            transport.close();
            server.stop(0);
        }
    }
}
