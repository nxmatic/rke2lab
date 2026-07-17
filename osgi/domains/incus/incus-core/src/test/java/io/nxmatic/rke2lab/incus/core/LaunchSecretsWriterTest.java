package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.auth.contract.AuthTokenContact;
import io.nxmatic.rke2lab.auth.contract.AuthTokenSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the comment-preserving {@code .secrets} upsert (§ provisioning-slice #10): the token
 * contact's values are written under the {@code github}/{@code flox} blocks, existing values are
 * replaced in place, comments and unrelated keys survive, an environment variable wins over the
 * contact, and a missing contact + empty environment leaves the file untouched. The environment is
 * injected (empty by default here) so the tests are hermetic regardless of the ambient shell.
 */
class LaunchSecretsWriterTest {

  /** An empty environment — no token variable set, so the contact is the only source. */
  private static final UnaryOperator<String> NO_ENV = name -> null;

  /** A contact that answers with fixed tokens per source. */
  private static AuthTokenContact contactWith(Map<AuthTokenSource, String> tokens) {
    return source -> Optional.ofNullable(tokens.get(source));
  }

  private static LaunchSecretsWriter writer(
      Optional<AuthTokenContact> contact, UnaryOperator<String> env) {
    return new LaunchSecretsWriter(contact, env);
  }

  @Test
  void it_appends_both_blocks_to_a_bare_file(@TempDir Path tmp) throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    Files.writeString(secrets, "# launch secrets\nregistry:\n  url: 'example.com'\n");

    writer(
            Optional.of(
                contactWith(
                    Map.of(AuthTokenSource.GITHUB, "ghtok", AuthTokenSource.FLOXHUB, "flxtok"))),
            NO_ENV)
        .ensureTokensPresent(secrets);

    final String result = Files.readString(secrets, StandardCharsets.UTF_8);
    assertTrue(result.contains("# launch secrets"), "the leading comment survives");
    assertTrue(result.contains("url: 'example.com'"), "the unrelated key survives");
    assertTrue(result.contains("github:"), "the github block is appended");
    assertTrue(result.contains("username: 'x-access-token'"), "the github username is written");
    assertTrue(result.contains("token: 'ghtok'"), "the github token is written");
    assertTrue(result.contains("flox:"), "the flox block is appended");
    assertTrue(result.contains("token: 'flxtok'"), "the flox token is written");
  }

  @Test
  void it_replaces_an_existing_token_preserving_comments(@TempDir Path tmp) throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    Files.writeString(
        secrets,
        """
        github:
          username: 'x-access-token'
          token: 'stale'  # rotated nightly
        """,
        StandardCharsets.UTF_8);

    writer(Optional.of(contactWith(Map.of(AuthTokenSource.GITHUB, "fresh"))), NO_ENV)
        .ensureTokensPresent(secrets);

    final String result = Files.readString(secrets, StandardCharsets.UTF_8);
    assertTrue(result.contains("token: 'fresh'"), "the value is replaced in place");
    assertTrue(result.contains("# rotated nightly"), "the trailing comment is kept");
    assertFalse(result.contains("stale"), "the stale token is gone");
  }

  @Test
  void an_environment_variable_wins_over_the_contact(@TempDir Path tmp) throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    Files.writeString(secrets, "", StandardCharsets.UTF_8);

    writer(
            Optional.of(contactWith(Map.of(AuthTokenSource.GITHUB, "from-contact"))),
            name -> "GH_TOKEN".equals(name) ? "from-env" : null)
        .ensureTokensPresent(secrets);

    final String result = Files.readString(secrets, StandardCharsets.UTF_8);
    assertTrue(result.contains("token: 'from-env'"), "the environment token wins");
    assertFalse(result.contains("from-contact"), "the contact is not asked when the env answers");
  }

  @Test
  void a_missing_contact_and_empty_environment_leaves_the_file_untouched(@TempDir Path tmp)
      throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    final String original = "# nothing to upsert\n";
    Files.writeString(secrets, original, StandardCharsets.UTF_8);

    writer(Optional.empty(), NO_ENV).ensureTokensPresent(secrets);

    assertEquals(
        original,
        Files.readString(secrets, StandardCharsets.UTF_8),
        "a blank token leaves the file byte-for-byte untouched");
  }
}
