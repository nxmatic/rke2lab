package io.seedmatic.rke2lab.osgi.boot.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link ClassRealm} contract: a world adapts to a capability face by type, returning the face
 * when this world offers it and {@link Optional#empty()} otherwise (semantics B — asymmetry is
 * real, not a defect). The default is the self-cast promoted from {@code ConsultingService.adapt}.
 */
class ClassRealmTest {

  interface Face {
    String hello();
  }

  /** A realm that IS a Face — the self-cast default should surface it. */
  static final class FaceRealm implements ClassRealm, Face {
    @Override
    public String hello() {
      return "hi";
    }
  }

  /** A realm that offers no faces — every adapt is empty. */
  static final class BareRealm implements ClassRealm {}

  @Test
  void adapt_returns_the_face_when_this_realm_is_it() {
    final Optional<Face> face = new FaceRealm().adapt(Face.class);
    assertTrue(face.isPresent(), "a realm that implements the face adapts to it");
    assertEquals("hi", face.get().hello());
  }

  @Test
  void adapt_is_empty_when_this_realm_does_not_offer_the_face() {
    assertTrue(
        new BareRealm().adapt(Face.class).isEmpty(),
        "a realm without the face returns empty, not null (semantics B)");
  }
}
