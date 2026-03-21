package io.nxmatic.rk2lab.manifests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

public final class WrapperGoArchiveAssets {

  public static final String ARCHIVE_CONFIGMAP_KEY = "wrapper-go.tar.b64";

  public static final String MANIFEST_CONFIGMAP_KEY = "wrapper-go.manifest.json";

  private static final String MANIFEST_FORMAT = "wrapper-go-archive-manifest-v1";

  private static final long TAR_ENTRY_EPOCH_MILLIS = 0L;

  private static final List<SourceAsset> SOURCE_ASSETS =
      List.of(
          new SourceAsset("/runtime/flox-containerd-shim/wrapper-go/go.mod", "wrapper-go/go.mod"),
          new SourceAsset("/runtime/flox-containerd-shim/wrapper-go/go.sum", "wrapper-go/go.sum"),
          new SourceAsset(
              "/runtime/flox-containerd-shim/wrapper-go/cmd/containerd-shim-flox-v2/main.go",
              "wrapper-go/cmd/containerd-shim-flox-v2/main.go"),
          new SourceAsset(
              "/runtime/flox-containerd-shim/wrapper-go/internal/wrapper/config.go",
              "wrapper-go/internal/wrapper/config.go"),
          new SourceAsset(
              "/runtime/flox-containerd-shim/wrapper-go/internal/wrapper/wrapper.go",
              "wrapper-go/internal/wrapper/wrapper.go"));

  private static final ArchiveBundle ARCHIVE_BUNDLE = buildArchiveBundle();

  private WrapperGoArchiveAssets() {}

  public static String archiveBase64() {
    return ARCHIVE_BUNDLE.archiveBase64();
  }

  public static String manifestJson() {
    return ARCHIVE_BUNDLE.manifestJson();
  }

  public static void materializeTo(Path outputDir) throws IOException {
    Files.writeString(
        outputDir.resolve(ARCHIVE_CONFIGMAP_KEY), archiveBase64(), StandardCharsets.UTF_8);
    Files.writeString(
        outputDir.resolve(MANIFEST_CONFIGMAP_KEY), manifestJson(), StandardCharsets.UTF_8);

    for (ArchiveEntry entry : ARCHIVE_BUNDLE.entries()) {
      Path target = outputDir.resolve(entry.path());
      Files.createDirectories(target.getParent());
      Files.write(target, entry.content());
    }
  }

  private static ArchiveBundle buildArchiveBundle() {
    try {
      List<ArchiveEntry> entries =
          SOURCE_ASSETS.stream().map(WrapperGoArchiveAssets::loadEntry).sorted().toList();

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

  private static ArchiveEntry loadEntry(SourceAsset asset) {
    try (InputStream input =
        WrapperGoArchiveAssets.class.getResourceAsStream(asset.classpathResource())) {
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

  private record SourceAsset(String classpathResource, String relativePath) {}

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
