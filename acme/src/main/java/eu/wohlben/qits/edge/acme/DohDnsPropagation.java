package eu.wohlben.qits.edge.acme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Requires two independent public recursive resolvers to observe the challenge value. */
public final class DohDnsPropagation implements DnsPropagation {

  private static final List<URI> RESOLVERS =
      List.of(
          URI.create("https://cloudflare-dns.com/dns-query"),
          URI.create("https://dns.google/resolve"));

  private final HttpClient http;
  private final ObjectMapper json;

  public DohDnsPropagation() {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.json = new ObjectMapper();
  }

  @Override
  public void await(String fqdn, String value, Duration timeout) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      boolean visible = true;
      for (URI resolver : RESOLVERS) {
        if (!visibleAt(resolver, fqdn, value)) {
          visible = false;
          break;
        }
      }
      if (visible) {
        return;
      }
      Thread.sleep(Duration.ofSeconds(5));
    }
    throw new IllegalStateException("DNS challenge did not propagate before its deadline");
  }

  private boolean visibleAt(URI resolver, String fqdn, String value) {
    try {
      String separator = resolver.getQuery() == null ? "?" : "&";
      URI query =
          URI.create(
              resolver
                  + separator
                  + "name="
                  + URLEncoder.encode(fqdn, StandardCharsets.UTF_8)
                  + "&type=TXT");
      HttpRequest request =
          HttpRequest.newBuilder(query)
              .timeout(Duration.ofSeconds(10))
              .header("Accept", "application/dns-json")
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return false;
      }
      JsonNode answers = json.readTree(response.body()).path("Answer");
      for (JsonNode answer : answers) {
        String data = answer.path("data").asText();
        if (unquote(data).equals(value)) {
          return true;
        }
      }
      return false;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1).replace("\\\"", "\"");
    }
    return value;
  }
}
