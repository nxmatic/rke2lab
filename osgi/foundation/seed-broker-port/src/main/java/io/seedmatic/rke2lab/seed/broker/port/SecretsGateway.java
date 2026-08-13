package io.seedmatic.rke2lab.seed.broker.port;

import java.util.Optional;
import java.util.Set;

/**
 * The operator-secrets membrane, published ONCE by the host as a service the whole run shares — the
 * read/write door onto {@code .secrets} (sops-governed), owned by the flat host that already reads
 * it (the {@code ConfigLoader} family) and grows the framework. Like {@link RunGate}, it lives in
 * the neutral seam both worlds see: the host registers its realisation into the framework it
 * booted, and an in-container scion resolves it by service lookup and calls back into host code —
 * same JVM (Felix embedded), only the interface is shared.
 *
 * <p>It speaks JSON only (a {@code String} subtree), never a domain type — the seam law. A consumer
 * maps the JSON to its own record in-container. So {@code .secrets} I/O stays 100% host-side (one
 * owner for read AND write), while the scion orchestrates when to rehydrate an anchor and when to
 * persist a freshly-established one.
 *
 * <p>The write is a SURGICAL upsert of one top-level block: it replaces only that block, never
 * round-tripping the whole file (which would strip every other secret's {@code # sops:encrypted}
 * comment and silently un-encrypt them). {@code encryptedLeaves} names the leaf keys that carry a
 * {@code # sops:encrypted} comment (the git sops filter encrypts those values at commit);
 * identifiers left out stay in the clear, matching the file's convention.
 */
public interface SecretsGateway {

  /**
   * The JSON of the {@code .secrets} subtree at {@code dottedPath} (e.g. {@code "githubApp"}), or
   * empty when the block is absent. The worktree copy is smudged plaintext, so the JSON is
   * readable.
   */
  Optional<String> read(String dottedPath);

  /**
   * Upsert the top-level block {@code key} from {@code json}, marking each leaf named in {@code
   * encryptedLeaves} with a {@code # sops:encrypted} comment. Surgical — only this block is
   * rewritten; every other block (and its comments) is preserved verbatim.
   */
  void write(String key, String json, Set<String> encryptedLeaves);
}
