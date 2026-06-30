package io.nxmatic.rke2lab.doctor.dsproof;

import io.nxmatic.rke2lab.doctor.port.InterventionJournal;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The DS boot proof's intervention READ port: an empty {@link InterventionJournal}
 * {@code @Component} so {@code DefaultHealthSystem}'s {@code @Reference} to the intervention
 * journal is satisfied and the institution activates. Yields no intervention Documents — the proof
 * exercises admission + consult, not drift-ledger folding.
 */
@Component(service = InterventionJournal.class)
public final class FakeInterventionJournal implements InterventionJournal {

  @Override
  public List<Document> entries() {
    return List.of();
  }
}
