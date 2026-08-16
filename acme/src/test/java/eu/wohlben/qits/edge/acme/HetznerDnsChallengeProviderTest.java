package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HetznerDnsChallengeProviderTest {

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void createsAMissingTxtRrsetWithAQuotedValue() throws Exception {
    List<Request> requests = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
          byte[] response =
              (exchange.getRequestMethod().equals("GET") ? "{\"rrsets\":[]}" : "{\"action\":{}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    var provider =
        new HetznerDnsChallengeProvider(
            HttpClient.newHttpClient(), new ObjectMapper(), api, "not-a-real-token", "wohlben.eu");

    provider.present("_acme-challenge.dev.wohlben.eu.", "digest");

    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).path()).contains("name=_acme-challenge.dev");
    assertThat(requests.get(1).method()).isEqualTo("POST");
    assertThat(requests.get(1).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets");
    assertThat(requests.get(1).body())
        .contains("\"name\":\"_acme-challenge.dev\"")
        .contains("\\\"digest\\\"");
  }

  private record Request(String method, String path, String body) {}
}
