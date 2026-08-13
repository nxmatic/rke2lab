package io.seedmatic.rke2lab.ghapp.contract;

/**
 * The ghapp domain's mint verb: from the sealed {@link GithubAppCredentials}, authenticate AS the
 * App (a JWT signed with its private key) and obtain a {@link MintedToken} scoped to one {@link
 * TokenScope}. The {@code ghapp-edge} satisfies it by signing the JWT and calling {@code POST
 * /app/installations/&#123;id&#125;/access_tokens} with the scope's permission subset.
 *
 * <p>Stateless and fail-fast: a mint either returns a token or throws — never a silent fallback to
 * another source (the predictability invariant). It does NOT create the App nor resolve its
 * credentials; it consumes credentials already sealed by the registration.
 */
public interface GithubAppMinter {

  /** The scoped installation token for {@code scope}, minted from {@code credentials}. */
  MintedToken mint(GithubAppCredentials credentials, TokenScope scope);
}
