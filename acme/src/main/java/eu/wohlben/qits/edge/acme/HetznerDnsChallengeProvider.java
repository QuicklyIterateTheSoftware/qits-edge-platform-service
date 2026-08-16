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
import java.util.List;
import java.util.Map;

/** DNS-01 mutations through Hetzner Cloud's RRSet API. */
public final class HetznerDnsChallengeProvider implements DnsChallengeProvider {

  private static final URI DEFAULT_API = URI.create("https://api.hetzner.cloud/v1/");

  private final HttpClient http;
  private final ObjectMapper json;
  private final URI api;
  private final String token;
  private final String zone;

  public HetznerDnsChallengeProvider(String token, String zone) {
    this(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
        new ObjectMapper(),
        DEFAULT_API,
        token,
        zone);
  }

  HetznerDnsChallengeProvider(
      HttpClient http, ObjectMapper json, URI api, String token, String zone) {
    this.http = http;
    this.json = json;
    this.api = api;
    this.token = token;
    this.zone = zone.toLowerCase(java.util.Locale.ROOT);
    if (token.isBlank()) {
      throw new IllegalArgumentException("Hetzner DNS token is blank");
    }
  }

  @Override
  public void present(String fqdn, String value) throws Exception {
    String name = relativeName(fqdn);
    String quoted = quote(value);
    JsonNode rrset = find(name);
    if (!rrset.isMissingNode()) {
      for (JsonNode record : rrset.path("records")) {
        if (quoted.equals(record.path("value").asText())) {
          return;
        }
      }
      post(
          rrsetUri(rrset.path("name").asText()) + "/actions/add_records",
          Map.of("records", List.of(Map.of("value", quoted, "comment", "QITS ACME validation"))));
      return;
    }
    post(
        "zones/" + encode(zone) + "/rrsets",
        Map.of(
            "name",
            name,
            "type",
            "TXT",
            "ttl",
            300,
            "records",
            List.of(Map.of("value", quoted, "comment", "QITS ACME validation"))));
  }

  @Override
  public void cleanup(String fqdn, String value) throws Exception {
    String name = relativeName(fqdn);
    String quoted = quote(value);
    JsonNode rrset = find(name);
    if (rrset.isMissingNode()) {
      return;
    }
    long matching =
        java.util.stream.StreamSupport.stream(rrset.path("records").spliterator(), false)
            .filter(record -> quoted.equals(record.path("value").asText()))
            .count();
    if (matching == 0) {
      return;
    }
    String path = rrsetUri(rrset.path("name").asText());
    if (rrset.path("records").size() == 1) {
      request("DELETE", path, null, true);
    } else {
      post(path + "/actions/remove_records", Map.of("records", List.of(Map.of("value", quoted))));
    }
  }

  private JsonNode find(String name) throws Exception {
    String path = "zones/" + encode(zone) + "/rrsets?type=TXT&name=" + encode(name);
    HttpResponse<String> response = request("GET", path, null, false);
    JsonNode rrsets = json.readTree(response.body()).path("rrsets");
    return rrsets.isArray() && !rrsets.isEmpty()
        ? rrsets.get(0)
        : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private String rrsetUri(String name) {
    return "zones/" + encode(zone) + "/rrsets/" + encode(name) + "/TXT";
  }

  private void post(String path, Object body) throws Exception {
    request("POST", path, json.writeValueAsBytes(body), false);
  }

  private HttpResponse<String> request(
      String method, String path, byte[] body, boolean acceptMissing) throws Exception {
    var builder =
        HttpRequest.newBuilder(api.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    }
    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 == 2 || (acceptMissing && response.statusCode() == 404)) {
      return response;
    }
    throw new IllegalStateException(
        "Hetzner DNS request failed with HTTP " + response.statusCode());
  }

  private String relativeName(String fqdn) {
    String name = fqdn.endsWith(".") ? fqdn.substring(0, fqdn.length() - 1) : fqdn;
    String suffix = "." + zone;
    if (name.equals(zone)) {
      return "@";
    }
    if (!name.endsWith(suffix)) {
      throw new IllegalArgumentException("challenge name is outside the configured zone");
    }
    return name.substring(0, name.length() - suffix.length());
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
