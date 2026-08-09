package io.nxmatic.rke2lab.manifests.cli.versions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.manifests.ingress.ComponentVersions;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The {@code manifests versions} bumper engine. Reads the single source of truth for the
 * bootstrap-layer component versions — {@link ComponentVersions#defaults()}, reachable FLAT because
 * it lives in the dual-realm {@code manifests-ingress-contract} — and, for each GitHub-sourced
 * component ({@link ComponentSources}), diffs the pinned version against the upstream releases,
 * reporting or applying the highest bump reachable within the authorised {@link
 * SemanticVersion.Level}.
 *
 * <p>The level is a CEILING on the jump, not a requirement: {@code report()}/{@code apply()} pick
 * the HIGHEST release the level admits, so {@code major} takes the newest release outright (it
 * "degrades" to a minor/micro when that is all that is newer), {@code minor} stays within the
 * current major, {@code micro} within the current major.minor.
 *
 * <p>{@link #apply} rewrites the {@code .<component>("<pin>")} literal in {@code
 * ComponentVersions.java} in place, and for a component that vendors an upstream manifest ({@link
 * ComponentSources#hasUpstreamYaml()}) it fetches the release asset for the target tag, drops it as
 * {@code release-<newPin>.yaml}, and removes the old one — asset FIRST, so a failed fetch never
 * leaves a pin pointing at a missing asset. The component id is not hardcoded twice: it is the
 * record-component name of {@code ComponentVersions}, introspected so the id set can never drift.
 * An optional {@code GITHUB_TOKEN} env var lifts the anonymous rate limit.
 */
public final class VersionBumper {

  /** An upstream release: the parsed semver AND the raw tag (needed verbatim for asset URLs). */
  public record Release(SemanticVersion version, String tag) {}

  private final SemanticVersion.Level level;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final Optional<String> githubToken;

  public VersionBumper(final SemanticVersion.Level level) {
    this.level = level;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.mapper = new ObjectMapper();
    this.githubToken =
        Optional.ofNullable(System.getenv("GITHUB_TOKEN"))
            .map(String::trim)
            .filter(token -> !token.isEmpty());
  }

  public SemanticVersion.Level level() {
    return level;
  }

  /**
   * One {@link VersionReport} per pinned component, in {@code ComponentVersions} declaration order.
   */
  public List<VersionReport> report() {
    final ComponentVersions pinned = ComponentVersions.defaults();
    final List<VersionReport> rows = new ArrayList<>();
    for (final RecordComponent component : ComponentVersions.class.getRecordComponents()) {
      rows.add(reportFor(component.getName(), accessorValue(component, pinned)));
    }
    return rows;
  }

  /**
   * Apply the bump in place: rewrite the pin literals in {@code componentVersionsSource} and
   * refresh the vendored assets under {@code resourcesRoot} (the {@code manifests-core} {@code
   * src/main/resources} dir). When {@code onlyComponent} is present, only that component is
   * touched; otherwise every GitHub-sourced component is. Returns a human-readable outcome line per
   * component considered.
   */
  public List<String> apply(
      final Optional<String> onlyComponent,
      final Path componentVersionsSource,
      final Path resourcesRoot) {
    final ComponentVersions pinned = ComponentVersions.defaults();
    String sourceText;
    try {
      sourceText = Files.readString(componentVersionsSource);
    } catch (final IOException failed) {
      return List.of("cannot read " + componentVersionsSource + ": " + failed.getMessage());
    }

    final List<String> outcomes = new ArrayList<>();
    for (final RecordComponent component : ComponentVersions.class.getRecordComponents()) {
      final String id = component.getName();
      if (onlyComponent.isPresent() && !onlyComponent.get().equals(id)) {
        continue;
      }
      final String oldPin = accessorValue(component, pinned);
      final Optional<ComponentSources> source = ComponentSources.byId(id);
      if (source.isEmpty()) {
        outcomes.add(skip(id, oldPin, "non-GitHub source — bump manually"));
        continue;
      }
      final Optional<SemanticVersion> current = SemanticVersion.parse(oldPin);
      final Optional<Release> target;
      try {
        target = highestAllowed(fetchStableReleases(source.get().githubRepo()), current);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        outcomes.add(skip(id, oldPin, "interrupted"));
        continue;
      } catch (final IOException unreachable) {
        outcomes.add(skip(id, oldPin, "unreachable: " + unreachable.getMessage()));
        continue;
      }
      if (current.isEmpty()) {
        outcomes.add(skip(id, oldPin, "current pin not semver"));
        continue;
      }
      if (target.isEmpty()) {
        outcomes.add(skip(id, oldPin, "already current under the " + level + " gate"));
        continue;
      }

      final String newPin = withPinStyle(oldPin, target.get().version());
      if (source.get().hasUpstreamYaml()) {
        try {
          refreshVendoredAsset(source.get(), target.get().tag(), oldPin, newPin, resourcesRoot);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          outcomes.add(skip(id, oldPin, "pin NOT changed — asset refresh interrupted"));
          continue;
        } catch (final IOException failed) {
          outcomes.add(
              skip(id, oldPin, "pin NOT changed — asset refresh failed: " + failed.getMessage()));
          continue;
        }
      }

      final String needle = "." + id + "(\"" + oldPin + "\")";
      if (!sourceText.contains(needle)) {
        outcomes.add(skip(id, oldPin, "literal " + needle + " not found — pin NOT changed"));
        continue;
      }
      sourceText = sourceText.replace(needle, "." + id + "(\"" + newPin + "\")");
      outcomes.add(
          id
              + "  "
              + oldPin
              + " -> "
              + newPin
              + (source.get().hasUpstreamYaml() ? "  [pin + asset]" : "  [pin]"));
    }

    try {
      Files.writeString(componentVersionsSource, sourceText);
    } catch (final IOException failed) {
      outcomes.add("cannot write " + componentVersionsSource + ": " + failed.getMessage());
    }
    return outcomes;
  }

  private static String skip(final String id, final String pin, final String why) {
    return id + "  " + pin + "  (unchanged: " + why + ")";
  }

  private VersionReport reportFor(final String id, final String pin) {
    final Optional<ComponentSources> source = ComponentSources.byId(id);
    if (source.isEmpty()) {
      return VersionReport.manual(
          id, pin, "non-GitHub source (chart / container tag) — bump manually");
    }
    try {
      final List<Release> releases = fetchStableReleases(source.get().githubRepo());
      final Optional<SemanticVersion> latest =
          releases.stream().map(Release::version).max(Comparator.naturalOrder());
      final Optional<SemanticVersion> current = SemanticVersion.parse(pin);
      if (latest.isEmpty()) {
        return VersionReport.manual(id, pin, "no stable release found upstream");
      }
      if (current.isEmpty()) {
        return new VersionReport(id, pin, latest, Optional.empty(), "current pin not semver");
      }
      final Optional<SemanticVersion> allowed =
          highestAllowed(releases, current).map(Release::version);
      return new VersionReport(id, pin, latest, allowed, "");
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return VersionReport.manual(id, pin, "interrupted");
    } catch (final IOException failed) {
      return VersionReport.manual(id, pin, "unreachable: " + failed.getMessage());
    }
  }

  /** The highest release the level gate admits above {@code current}, empty if none. */
  private Optional<Release> highestAllowed(
      final List<Release> releases, final Optional<SemanticVersion> current) {
    if (current.isEmpty()) {
      return Optional.empty();
    }
    return releases.stream()
        .filter(release -> current.get().allows(release.version(), level))
        .max(Comparator.comparing(Release::version));
  }

  private List<Release> fetchStableReleases(final String repo)
      throws IOException, InterruptedException {
    final JsonNode releases =
        getJson("https://api.github.com/repos/" + repo + "/releases?per_page=100");
    final List<Release> stable = new ArrayList<>();
    for (final JsonNode release : releases) {
      if (release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) {
        continue;
      }
      final String tag = release.path("tag_name").asText(null);
      SemanticVersion.parse(tag).ifPresent(version -> stable.add(new Release(version, tag)));
    }
    return stable;
  }

  /**
   * Fetch the release asset for {@code tag} and drop it as {@code release-<newPin>.yaml} under the
   * component's vendored dir, then remove the {@code release-<oldPin>.yaml} it replaces. Fetch (and
   * write) happen before the old file is removed, so a failure leaves the working tree intact.
   */
  private void refreshVendoredAsset(
      final ComponentSources source,
      final String tag,
      final String oldPin,
      final String newPin,
      final Path resourcesRoot)
      throws IOException, InterruptedException {
    final byte[] asset = fetchReleaseAsset(source.githubRepo(), tag, source.releaseAssetName());
    final Path dir = resourcesRoot.resolve(source.vendoredResourceDir());
    Files.createDirectories(dir);
    final Path newFile = dir.resolve("release-" + newPin + ".yaml");
    Files.write(newFile, asset);
    final Path oldFile = dir.resolve("release-" + oldPin + ".yaml");
    if (!oldFile.equals(newFile)) {
      Files.deleteIfExists(oldFile);
    }
  }

  /** The bytes of {@code assetName} on the {@code tag} release of {@code repo}. */
  private byte[] fetchReleaseAsset(final String repo, final String tag, final String assetName)
      throws IOException, InterruptedException {
    final JsonNode release =
        getJson("https://api.github.com/repos/" + repo + "/releases/tags/" + tag);
    for (final JsonNode asset : release.path("assets")) {
      if (assetName.equals(asset.path("name").asText(null))) {
        final String url = asset.path("browser_download_url").asText(null);
        if (url == null) {
          throw new IOException(
              "asset " + assetName + " has no download url on " + repo + "@" + tag);
        }
        return getBytes(url);
      }
    }
    throw new IOException("asset " + assetName + " not found on " + repo + "@" + tag);
  }

  private JsonNode getJson(final String url) throws IOException, InterruptedException {
    final HttpResponse<String> response =
        send(url, "application/vnd.github+json", HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " from " + url);
    }
    return mapper.readTree(response.body());
  }

  private byte[] getBytes(final String url) throws IOException, InterruptedException {
    final HttpResponse<byte[]> response =
        send(url, "application/octet-stream", HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " from " + url);
    }
    return response.body();
  }

  private <T> HttpResponse<T> send(
      final String url, final String accept, final HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(30))
            .GET();
    githubToken.ifPresent(token -> request.header("Authorization", "Bearer " + token));
    return http.send(request.build(), handler);
  }

  /**
   * Keep the pin's leading-{@code v} convention (capiCore uses {@code v1.9.4}; tailscale {@code
   * 1.82.0}).
   */
  private static String withPinStyle(final String oldPin, final SemanticVersion version) {
    return (oldPin.startsWith("v") ? "v" : "") + version;
  }

  private static String accessorValue(
      final RecordComponent component, final ComponentVersions pinned) {
    try {
      return (String) component.getAccessor().invoke(pinned);
    } catch (final ReflectiveOperationException impossible) {
      throw new IllegalStateException(
          "ComponentVersions accessor " + component.getName() + " is not readable", impossible);
    }
  }
}
