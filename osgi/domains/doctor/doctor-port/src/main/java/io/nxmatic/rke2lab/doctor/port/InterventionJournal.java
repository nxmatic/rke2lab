package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.world.gateway.port.Document;
import java.util.List;

/**
 * The Layer-1 host READ port for the one fixed intervention-ledger stack: the host walks the
 * ledger's stack history and yields one opaque {@code intervention} {@link Document} per entry,
 * WITHOUT interpreting its content. Each Document's payload is the RAW {@code interventions} output
 * blob from that history entry.
 *
 * <p>OSGi rebuilds the {@code InterventionLedger} from these blobs INSIDE the bundle realm (the
 * moved {@code InterventionReader}); no {@code doctor.records} type crosses this seam — only {@link
 * Document}. Unkeyed: there is exactly one ledger stack ({@code intervention-ledger/dev}), so there
 * is nothing to key the read by.
 */
public interface InterventionJournal {

  /**
   * The ledger's history, oldest first, one {@code intervention} {@link Document} per entry. An
   * unwritten ledger leaves no history and yields an empty list; a present-but-unreadable history
   * is exceptional and propagates rather than masking the dishonesty the ledger exists to kill.
   */
  List<Document> entries();
}
