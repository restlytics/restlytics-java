package com.restlytics.spring;

import com.restlytics.RestlyticsConfig;
import com.restlytics.Tracer;
import com.restlytics.Transport;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HttpPropagationTest {

    private static final String SAMPLED =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String UNSAMPLED =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";

    @Test
    void interceptorInjectsTheRecordedClientSpanContext() throws Exception {
        CaptureTransport transport = new CaptureTransport();
        Tracer tracer = new Tracer(transport, "test", "test", 1.0, 100);
        tracer.startServerSpan("GET /proxy", SAMPLED);
        RestlyticsClientHttpInterceptor interceptor = new RestlyticsClientHttpInterceptor(
                tracer,
                new RestlyticsConfig());
        MutableRequest request = new MutableRequest();

        interceptor.intercept(request, new byte[0], (outgoing, body) -> ok());
        String traceparent = request.headers.getFirst("traceparent");
        tracer.finishServerSpan();

        assertTrue(traceparent.matches(
                "^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01$"));
        String childSpanId = traceparent.split("-")[2];
        assertTrue(transport.payload.contains("\"spanId\":\"" + childSpanId + "\""));
        assertTrue(transport.payload.contains(
                "\"parentSpanId\":\"" + tracer.rootSpanId() + "\""));
    }

    @Test
    void interceptorPropagatesUnsampledContextWithoutRecording() throws Exception {
        CaptureTransport transport = new CaptureTransport();
        Tracer tracer = new Tracer(transport, "test", "test", 1.0, 100);
        tracer.startServerSpan("GET /proxy", UNSAMPLED);
        RestlyticsClientHttpInterceptor interceptor = new RestlyticsClientHttpInterceptor(
                tracer,
                new RestlyticsConfig());
        MutableRequest request = new MutableRequest();

        ClientHttpResponse response = interceptor.intercept(
                request,
                new byte[0],
                (outgoing, body) -> ok());
        tracer.finishServerSpan();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(request.headers.getFirst("traceparent").matches(
                "^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-00$"));
        assertFalse(transport.called);
    }

    private static ClientHttpResponse ok() {
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() {
                return HttpStatus.OK;
            }

            @Override
            public String getStatusText() {
                return "OK";
            }

            @Override
            public void close() {
            }

            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
    }

    private static final class MutableRequest implements HttpRequest {
        private final HttpHeaders headers = new HttpHeaders();

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return URI.create("https://api.example.test/orders?token=secret");
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }

    private static final class CaptureTransport implements Transport {
        private boolean called;
        private String payload = "";

        @Override
        public void send(String jsonBody) {
            called = true;
            payload = jsonBody;
        }
    }
}
