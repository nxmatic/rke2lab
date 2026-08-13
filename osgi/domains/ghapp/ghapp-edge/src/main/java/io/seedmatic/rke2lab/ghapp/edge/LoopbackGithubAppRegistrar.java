package io.seedmatic.rke2lab.ghapp.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppRegistrar;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised ghapp registration edge: satisfies {@link GithubAppRegistrar} by running GitHub's
 * App-manifest flow over an ephemeral, loopback-only HTTP endpoint. The endpoint is a raw {@link
 * ServerSocket} on {@code 127.0.0.1:8099} (java.net, boot-delegated — no {@code
 * com.sun.net.httpserver} nor any system-package cost), stood up only for the ceremony and torn
 * down once the {@code code} is caught.
 *
 * <p>Two routes: {@code GET /} serves an auto-submitting form that POSTs the manifest to {@code
 * github.com/organizations/<org>/settings/apps/new} in the operator's logged-in browser; {@code GET
 * /callback?code=…} catches GitHub's post-approval redirect. The edge then exchanges the {@code
 * code} at {@code POST /app-manifests/&#123;code&#125;/conversions} (no auth) for the App id and
 * PEM, resolves the installation id with a JWT-authed {@code GET /app/installations}, and returns
 * the sealed-ready {@link GithubAppCredentials}.
 *
 * <p>Tagged {@code rke2lab.gardening=cultivating}: it opens a socket and waits on a human, so a
 * survey/preview frontier filters it out and the registration scenario PENDS.
 */
@Component(service = GithubAppRegistrar.class, property = "rke2lab.gardening=cultivating")
public final class LoopbackGithubAppRegistrar implements GithubAppRegistrar {

  private static final Logger LOG = LoggerFactory.getLogger(LoopbackGithubAppRegistrar.class);
  private static final int PORT = 8099;
  private static final URI API = URI.create("https://api.github.com");
  private static final String API_VERSION = "2022-11-28";

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public GithubAppCredentials register(String manifestJson, Consumer<URI> onEndpointReady)
      throws InterruptedException {
    final String org = orgFrom(manifestJson);
    final String state = UUID.randomUUID().toString();
    try (ServerSocket server = new ServerSocket()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
      final URI url = URI.create("http://127.0.0.1:" + PORT + "/");
      LOG.warn("GitHub App registration pending — open {} to create the App", url);
      onEndpointReady.accept(url);
      final String code = awaitCode(server, manifestJson, org, state);
      return convert(code);
    } catch (IOException e) {
      throw new UncheckedIOException("the registration endpoint failed", e);
    }
  }

  private String awaitCode(ServerSocket server, String manifestJson, String org, String state)
      throws IOException, InterruptedException {
    while (true) {
      if (Thread.interrupted()) {
        throw new InterruptedException("registration wait interrupted");
      }
      try (Socket socket = server.accept()) {
        final String path = pathOf(readRequestLine(socket.getInputStream()));
        if (path.startsWith("/callback")) {
          final String code = queryParam(path, "code");
          respond(socket.getOutputStream(), 200, DONE_HTML);
          if (code != null && !code.isBlank()) {
            return code;
          }
        } else if (path.equals("/") || path.isEmpty()) {
          respond(socket.getOutputStream(), 200, manifestForm(manifestJson, org, state));
        } else {
          respond(socket.getOutputStream(), 404, "not found");
        }
      }
    }
  }

  private GithubAppCredentials convert(String code) {
    final HttpResponse<String> response =
        send(request("/app-manifests/" + code + "/conversions", null));
    if (response.statusCode() != 201) {
      throw new IllegalStateException(
          "App-manifest conversion failed: HTTP " + response.statusCode() + " " + response.body());
    }
    final JsonNode app = read(response.body());
    final String appId = requiredText(app, "id");
    final String pem = requiredText(app, "pem");
    return new GithubAppCredentials(appId, resolveInstallationId(appId, pem), pem);
  }

  private String resolveInstallationId(String appId, String pem) {
    final PrivateKey key = AppJwt.readPrivateKey(pem);
    final String jwt = AppJwt.issue(appId, key, Instant.now(), mapper);
    final HttpResponse<String> response = send(request("/app/installations", jwt));
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "listing App installations failed: HTTP "
              + response.statusCode()
              + " "
              + response.body());
    }
    final JsonNode installations = read(response.body());
    if (!installations.isArray() || installations.isEmpty()) {
      throw new IllegalStateException(
          "the App was created but is not installed on the org yet — install it in the browser "
              + "(GitHub → the new App → Install), then re-run the grow");
    }
    return requiredText(installations.get(0), "id");
  }

  private HttpRequest request(String path, @Nullable String jwt) {
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(API.resolve(path))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION);
    if (jwt != null) {
      builder.header("Authorization", "Bearer " + jwt).GET();
    } else {
      builder.POST(HttpRequest.BodyPublishers.noBody());
    }
    return builder.build();
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException("GitHub " + request.uri() + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub " + request.uri() + " interrupted", e);
    }
  }

  private JsonNode read(String body) {
    try {
      return mapper.readTree(body);
    } catch (IOException e) {
      throw new UncheckedIOException("could not parse GitHub response", e);
    }
  }

  private static String orgFrom(String manifestJson) {
    final ObjectMapper mapper = new ObjectMapper();
    try {
      final String url = requiredText(mapper.readTree(manifestJson), "url");
      final String[] segments = URI.create(url).getPath().split("/");
      if (segments.length < 2 || segments[1].isBlank()) {
        throw new IllegalArgumentException("manifest url has no org: " + url);
      }
      return segments[1];
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the App manifest", e);
    }
  }

  private static String manifestForm(String manifestJson, String org, String state) {
    final String action =
        "https://github.com/organizations/" + org + "/settings/apps/new?state=" + state;
    return "<!doctype html><html><head><title>Create the GitHub App</title></head>"
        + "<body onload=\"document.forms[0].submit()\">"
        + "<form action=\""
        + htmlAttr(action)
        + "\" method=\"post\">"
        + "<input type=\"hidden\" name=\"manifest\" value=\""
        + htmlAttr(manifestJson)
        + "\">"
        + "<noscript><button type=\"submit\">Create the GitHub App</button></noscript>"
        + "</form></body></html>";
  }

  private static final String DONE_HTML =
      "<!doctype html><html><body>The GitHub App is being created — you can close this tab.</body></html>";

  private static String readRequestLine(InputStream in) throws IOException {
    final BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    final String line = reader.readLine();
    return line == null ? "" : line;
  }

  private static String pathOf(String requestLine) {
    final String[] parts = requestLine.split(" ");
    return parts.length < 2 ? "" : parts[1];
  }

  @Nullable
  private static String queryParam(String path, String name) {
    final int q = path.indexOf('?');
    if (q < 0) {
      return null;
    }
    for (final String pair : path.substring(q + 1).split("&")) {
      final int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static void respond(OutputStream out, int status, String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    final String head =
        "HTTP/1.1 "
            + status
            + " \r\n"
            + "Content-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: "
            + bytes.length
            + "\r\n"
            + "Connection: close\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.US_ASCII));
    out.write(bytes);
    out.flush();
  }

  private static String htmlAttr(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String requiredText(JsonNode node, String field) {
    final JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalStateException("GitHub response missing '" + field + "'");
    }
    return value.asText();
  }
}
