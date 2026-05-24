// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.flox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

public final class WrapperGoArchiveAssets {

  public static final String ARCHIVE_CONFIGMAP_KEY = "wrapper-go.tar.b64";

  public static final String MANIFEST_CONFIGMAP_KEY = "wrapper-go.manifest.json";

  private static final String MANIFEST_FORMAT = "wrapper-go-archive-manifest-v1";

  private static final long TAR_ENTRY_EPOCH_MILLIS = 0L;

  private final Class<?> resourceAnchor;
  private final List<SourceAsset> sourceAssets;
  private final ArchiveBundle archiveBundle;

  private WrapperGoArchiveAssets(Builder builder) {
    this.resourceAnchor = builder.resourceAnchor;
    this.sourceAssets = List.copyOf(builder.sourceAssets);
    this.archiveBundle = buildArchiveBundle();
  }

  public static Builder builder() {
    return new Builder();
  }

  public String archiveBase64() {
    return archiveBundle.archiveBase64();
  }

  public String manifestJson() {
    return archiveBundle.manifestJson();
  }

  public void materializeTo(Path outputDir) throws IOException {
    Files.writeString(
        outputDir.resolve(ARCHIVE_CONFIGMAP_KEY), archiveBase64(), StandardCharsets.UTF_8);
    Files.writeString(
        outputDir.resolve(MANIFEST_CONFIGMAP_KEY), manifestJson(), StandardCharsets.UTF_8);

    for (ArchiveEntry entry : archiveBundle.entries()) {
      Path target = outputDir.resolve(entry.path());
      Files.createDirectories(target.getParent());
      Files.write(target, entry.content());
    }
  }

  private ArchiveBundle buildArchiveBundle() {
    try {
      List<ArchiveEntry> entries = sourceAssets.stream().map(this::loadEntry).sorted().toList();

      byte[] archiveBytes = buildArchive(entries);
      String archiveSha256 = sha256Hex(archiveBytes);
      long archiveSize = archiveBytes.length;
      String archiveBase64 = Base64.getEncoder().encodeToString(archiveBytes);
      String manifestJson = buildManifestJson(entries, archiveSize, archiveSha256);
      return new ArchiveBundle(
          entries, archiveBytes, archiveBase64, manifestJson, archiveSize, archiveSha256);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed generating wrapper-go archive assets", ex);
    }
  }

  private ArchiveEntry loadEntry(SourceAsset asset) {
    try (InputStream input = resourceAnchor.getResourceAsStream(asset.classpathResource())) {
      if (input == null) {
        throw new IllegalStateException(
            "Missing wrapper-go resource: " + asset.classpathResource());
      }
      byte[] content = input.readAllBytes();
      return new ArchiveEntry(asset.relativePath(), content, content.length, sha256Hex(content));
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed reading wrapper-go resource: " + asset.classpathResource(), ex);
    }
  }

  private static byte[] buildArchive(List<ArchiveEntry> entries) throws IOException {
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tarStream =
        new TarArchiveOutputStream(byteStream, StandardCharsets.UTF_8.name())) {
      tarStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      tarStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
      for (ArchiveEntry entry : entries) {
        TarArchiveEntry tarEntry = new TarArchiveEntry(entry.path());
        tarEntry.setModTime(TAR_ENTRY_EPOCH_MILLIS);
        tarEntry.setMode(0644);
        tarEntry.setSize(entry.size());
        tarStream.putArchiveEntry(tarEntry);
        tarStream.write(entry.content());
        tarStream.closeArchiveEntry();
      }
      tarStream.finish();
    }
    return byteStream.toByteArray();
  }

  private static String buildManifestJson(
      List<ArchiveEntry> entries, long archiveSize, String archiveSha256) {
    StringBuilder builder = new StringBuilder();
    builder.append("{\n");
    builder.append("  \"format\": \"").append(MANIFEST_FORMAT).append("\",\n");
    builder.append("  \"archive\": {\n");
    builder.append("    \"path\": \"").append(ARCHIVE_CONFIGMAP_KEY).append("\",\n");
    builder.append("    \"encoding\": \"base64\",\n");
    builder.append("    \"size\": ").append(archiveSize).append(",\n");
    builder.append("    \"sha256\": \"").append(archiveSha256).append("\"\n");
    builder.append("  },\n");
    builder.append("  \"entries\": [\n");
    for (int i = 0; i < entries.size(); i++) {
      ArchiveEntry entry = entries.get(i);
      builder
          .append("    {\"path\": \"")
          .append(jsonEscape(entry.path()))
          .append("\", \"size\": ")
          .append(entry.size())
          .append(", \"sha256\": \"")
          .append(entry.sha256())
          .append("\"}");
      if (i + 1 < entries.size()) {
        builder.append(',');
      }
      builder.append('\n');
    }
    builder.append("  ]\n");
    builder.append("}\n");
    return builder.toString();
  }

  private static String jsonEscape(String value) {
    StringBuilder builder = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '\\' -> builder.append("\\\\");
        case '"' -> builder.append("\\\"");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (Character.isISOControl(ch)) {
            builder.append("\\u").append(String.format(Locale.ROOT, "%04x", (int) ch));
          } else {
            builder.append(ch);
          }
        }
      }
    }
    return builder.toString();
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 digest is unavailable", ex);
    }
  }

  public static final class Builder {
    private Class<?> resourceAnchor = WrapperGoArchiveAssets.class;

    private final List<SourceAsset> sourceAssets = new ArrayList<>();

    private Builder() {
      addDefaultSourceAssets();
    }

    public Builder addDefaultSourceAssets() {
      // Go module files
      addSourceAsset(
          source ->
              source
                  .classpathResource("/runtime/flox-runtime/nri-plugin/go.mod")
                  .relativePath("nri-plugin/go.mod"));
      addSourceAsset(
          source ->
              source
                  .classpathResource("/runtime/flox-runtime/nri-plugin/go.sum")
                  .relativePath("nri-plugin/go.sum"));

      // NRI plugin (only approach - shim removed)
      addSourceAsset(
          source ->
              source
                  .classpathResource("/runtime/flox-runtime/nri-plugin/cmd/flox-nri-plugin/main.go")
                  .relativePath("nri-plugin/cmd/flox-nri-plugin/main.go"));
      addSourceAsset(
          source ->
              source
                  .classpathResource("/runtime/flox-runtime/nri-plugin/pkg/nri/plugin.go")
                  .relativePath("nri-plugin/pkg/nri/plugin.go"));
      return this;
    }

    public Builder resourceAnchor(Class<?> resourceAnchor) {
      this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
      return this;
    }

    public Builder addSourceAsset(Consumer<SourceAsset.Builder> sourceAssetBuilder) {
      Objects.requireNonNull(sourceAssetBuilder, "sourceAssetBuilder");
      SourceAsset.Builder builder = SourceAsset.builder();
      sourceAssetBuilder.accept(builder);
      return addSourceAsset(builder.build());
    }

    public Builder addSourceAsset(SourceAsset sourceAsset) {
      sourceAssets.add(Objects.requireNonNull(sourceAsset, "sourceAsset"));
      return this;
    }

    public Builder clearSourceAssets() {
      sourceAssets.clear();
      return this;
    }

    public WrapperGoArchiveAssets build() {
      return new WrapperGoArchiveAssets(this);
    }
  }

  public static final class SourceAsset {
    private final String classpathResource;
    private final String relativePath;

    private SourceAsset(Builder builder) {
      this.classpathResource =
          Objects.requireNonNull(builder.classpathResource, "classpathResource");
      this.relativePath = Objects.requireNonNull(builder.relativePath, "relativePath");
    }

    public static Builder builder() {
      return new Builder();
    }

    public String classpathResource() {
      return classpathResource;
    }

    public String relativePath() {
      return relativePath;
    }

    public static final class Builder {
      private String classpathResource;
      private String relativePath;

      private Builder() {}

      public Builder classpathResource(String classpathResource) {
        this.classpathResource = classpathResource;
        return this;
      }

      public Builder relativePath(String relativePath) {
        this.relativePath = relativePath;
        return this;
      }

      public SourceAsset build() {
        return new SourceAsset(this);
      }
    }
  }

  private record ArchiveBundle(
      List<ArchiveEntry> entries,
      byte[] archiveBytes,
      String archiveBase64,
      String manifestJson,
      long archiveSize,
      String archiveSha256) {}

  private record ArchiveEntry(String path, byte[] content, long size, String sha256)
      implements Comparable<ArchiveEntry> {
    @Override
    public int compareTo(ArchiveEntry other) {
      return path.compareTo(other.path);
    }
  }
}
