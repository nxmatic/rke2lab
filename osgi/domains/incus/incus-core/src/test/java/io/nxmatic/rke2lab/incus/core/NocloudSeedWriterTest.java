package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the unwrap of the {@code cloud-config} ConfigMap into the NoCloud seed (§
 * provisioning-slice #2): the three {@code data} keys ({@code userData}/{@code metaData}/{@code
 * networkData}) land byte-for-byte under the NoCloud file names, a Secret's base64 {@code data} is
 * decoded, an incomplete source fails loud, and pre-existing files are cleared.
 */
class NocloudSeedWriterTest {

  private final NocloudSeedWriter writer = new NocloudSeedWriter();

  @Test
  void it_unwraps_a_configmap_byte_for_byte(@TempDir Path tmp) throws IOException {
    final Path source = Files.createDirectories(tmp.resolve("cloud-config"));
    final Path seed = tmp.resolve("cloud.d");
    Files.writeString(
        source.resolve("cloud-config.yaml"),
        """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: cloud-config
        data:
          userData: |
            #cloud-config
            hostname: bioskop-master
          metaData: |
            instance-id: bioskop-master
          networkData: |
            version: 2
        """,
        StandardCharsets.UTF_8);

    writer.unwrap(source, seed);

    assertEquals(
        "#cloud-config\nhostname: bioskop-master\n",
        Files.readString(seed.resolve("user-data"), StandardCharsets.UTF_8),
        "user-data is written from the userData key");
    assertEquals(
        "instance-id: bioskop-master\n",
        Files.readString(seed.resolve("meta-data"), StandardCharsets.UTF_8),
        "meta-data is written from the metaData key");
    assertEquals(
        "version: 2\n",
        Files.readString(seed.resolve("network-config"), StandardCharsets.UTF_8),
        "network-config is written from the networkData key");
  }

  @Test
  void it_base64_decodes_a_secret(@TempDir Path tmp) throws IOException {
    final Path source = Files.createDirectories(tmp.resolve("cloud-config"));
    final Path seed = tmp.resolve("cloud.d");
    final String user =
        Base64.getEncoder().encodeToString("#cloud-config\n".getBytes(StandardCharsets.UTF_8));
    final String meta =
        Base64.getEncoder().encodeToString("instance-id: x\n".getBytes(StandardCharsets.UTF_8));
    final String net =
        Base64.getEncoder().encodeToString("version: 2\n".getBytes(StandardCharsets.UTF_8));
    Files.writeString(
        source.resolve("secret.yaml"),
        "apiVersion: v1\nkind: Secret\ndata:\n  userData: "
            + user
            + "\n  metaData: "
            + meta
            + "\n  networkData: "
            + net
            + "\n",
        StandardCharsets.UTF_8);

    writer.unwrap(source, seed);

    assertEquals(
        "#cloud-config\n",
        Files.readString(seed.resolve("user-data"), StandardCharsets.UTF_8),
        "a Secret's base64 data is decoded");
  }

  @Test
  void it_clears_stale_seed_files(@TempDir Path tmp) throws IOException {
    final Path source = Files.createDirectories(tmp.resolve("cloud-config"));
    final Path seed = Files.createDirectories(tmp.resolve("cloud.d"));
    Files.writeString(seed.resolve("stale"), "old", StandardCharsets.UTF_8);
    Files.writeString(
        source.resolve("cloud-config.yaml"),
        "kind: ConfigMap\ndata:\n  userData: a\n  metaData: b\n  networkData: c\n",
        StandardCharsets.UTF_8);

    writer.unwrap(source, seed);

    assertFalse(Files.exists(seed.resolve("stale")), "a stale seed file is cleared before writing");
    assertTrue(Files.exists(seed.resolve("user-data")), "the fresh seed is written");
  }

  @Test
  void an_incomplete_source_fails_loud(@TempDir Path tmp) throws IOException {
    final Path source = Files.createDirectories(tmp.resolve("cloud-config"));
    final Path seed = tmp.resolve("cloud.d");
    Files.writeString(
        source.resolve("cloud-config.yaml"),
        "kind: ConfigMap\ndata:\n  userData: a\n",
        StandardCharsets.UTF_8);

    final IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> writer.unwrap(source, seed));
    assertTrue(
        failure.getMessage().contains("metaData") && failure.getMessage().contains("networkData"),
        "the failure names the missing payloads");
  }
}
