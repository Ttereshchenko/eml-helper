package com.github.ttereshchenko.mailkit.smtp.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Reusable Mailpit container for SMTP integration tests. Mailpit listens on 1025 for SMTP and
 * 8025 for the HTTP JSON API used by tests to verify received messages.
 */
public final class MailpitContainer extends GenericContainer<MailpitContainer> {

    private static final DockerImageName IMAGE = DockerImageName.parse("axllent/mailpit:v1.21");

    public MailpitContainer() {
        super(IMAGE);
        withExposedPorts(1025, 8025);
        waitingFor(new WaitAllStrategy()
                .withStrategy(Wait.forListeningPorts(1025))
                .withStrategy(Wait.forHttp("/api/v1/info").forPort(8025))
                .withStartupTimeout(Duration.ofSeconds(60)));
    }

    public int smtpPort() {
        return getMappedPort(1025);
    }

    public int httpPort() {
        return getMappedPort(8025);
    }

    public String httpApiBase() {
        return "http://" + getHost() + ":" + httpPort();
    }

    /**
     * Fetch the parsed JSON response of {@code GET /api/v1/messages}. Tests perform substring
     * assertions on the body to avoid pulling in a JSON parser dependency.
     */
    public String fetchMessagesJson() throws Exception {
        try (var client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create(httpApiBase() + "/api/v1/messages"))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        }
    }
}
