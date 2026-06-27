# RESUME — dbus-systemd-edge implementation (2026-06-23)

AVANT TOUT : lis `.claude/hub/instructions.md` § "Progress narration" et applique-la toute la session.

## Où on en est : LES 3 STEPS DU CHANTIER SONT FAITS. État STABLE, tout vert.

Worktree `feature/dbus-systemd-edge` (sur design/pre-integration @ 43c3c4f7). On ALIGNE la codebase
sur `docs/architecture/osgi/dbus-systemd-edge-spec.adoc`. Étapes vertes commitées, squash à la fin.

## Commits (verts, dans l'ordre)
- `c82b6536` — 1a : seam `SystemdRuntimeProbe` + `SystemdProbeRequest` (systemd-port).
- `8fba2747` — 1b : module edge, `@Component DbusSystemdProbe`, 3 jars dbus-java nichés `lib:=true`.
- `a20d2c7c` — refactor (orthogonal) : `FelixFrameworkExtension` → `OutOfContainerFrameworkExtension`.
- `e5772eac` — 1c : host rewire (awaitService) + déblocage frontière de boot
  (`HostClassLoaderView.resolves()` compte les packages du `ModuleLayer.boot()`).
- `89acb6bd` — boot test edge : `DbusSystemdEdgeBootTest` (out-of-container, extension-only). Prouve
  SCR publie le probe typé + ServiceLoader trouve `TcpTransportProvider` dans le Bundle-ClassPath
  (mode d'échec : connection-refused = OK, pas "no dbus-java-transport found").
- `327c8c4c` — refactor build : `osgi/bundle-test-parent` single-source la recette test des fragments.
- `07707cf0` — step 2 : `DbusTcpSpecialist` → doctor-core, libéré de BootstrapConfig (lit
  l'observation ; le gate promeut adapterHost/Port/nodeName en clés plates de details). Rejoint le
  roster standard. `DoctorAssembly.assemble` perd son param config. Test migré vers doctor-core-test.
- `448400dc` — step 3 : `SystemdUnitId` typé dans systemd-port (bareName / serviceUnitName). Producteur
  (manifests StorageStage) + consommateur (DbusTcpSpecialist) + 3 tests le référencent. Seul literal
  restant = la constante de l'enum. Harnais in-container system-exportent le seam systemd.port.
- `46a507db` — refactor doctor : single-source du format humanHint `incus exec <node> -- systemctl
  restart <unit>` (helper `DbusTcpSpecialist.restartUnitCommand`, le test de contrat l'asserte via lui).

## CHANTIER dbus-systemd CLÔTURÉ. Décision (avec l'user) : NE PAS élargir le scope sur cette branche.

Scan single-source des edges = propre : pulumi-edge ✅ (OUTPUT_KEY déjà constante), ssh-to-age ✅
(porte de passage, literals locaux, pas de duplication cross-module), dbus-systemd ✅. Taxonomie clé :
SONDES (observent → snapshot → fait → doctor, médié par le control-node : pulumi, dbus-systemd) vs
PORTES (egress/conversion, échec = exception, pas de fait : ssh-to-age, empruntable inline par tous).

## Les 3 edges restants (identifiés) : incus / cluster / host-fs (cibles « playable » des 6)
NE PAS coder direct : chaque edge mérite son travail de modèle (contact ? port nommé par concern ?
sonde ou porte ? snapshot→fait ?) comme dbus-systemd a eu sa spec. Pattern établi+documenté → rapide.
**FORME WORKTREE = décision de la session d'INTÉGRATION, pas la nôtre** : elle avait prévu les 3
ENSEMBLE dans UN worktree partagé (plan external-edges). L'user confirme avec elle. Ne PAS présumer
un-worktree-par-edge ; déférer à [[external-edges-chantier-handoff]] / la session d'intégration.

## Backlog (rien d'urgent)
- **SchemaRef dbus-tcp/* à single-sourcer** : `"dbus-tcp/connection-refused/v1"` + `"dbus-tcp/declined/v1"`
  dupliqués (DbusTcpSpecialist + 3 tests). MÊME principe que step 3 mais domaine DOCTOR (id de schéma
  d'assessment, PAS systemd → constantes côté doctor, pas SystemdUnitId). Repéré par l'user.
- **Forme de packaging** (spec OPEN) : pulumi-edge NE PEUT PAS migrer en OSGi (grpc/netty non-playable) ;
  réconciliation groupé+testkit vs plat tenue ouverte, basse priorité.
- [[dependency-analyze-gate-backlog]] — garde anti-drift `dependency:analyze` failOnWarning (215
  warnings, ~95% faux positifs structurels OSGi à calibrer ; inventaire mesuré dans la mémoire).
- [[dbus-systemd-probe-poll-backlog]] — la sonde rouvre une connexion par tick ; systemd émet des
  SIGNALS dbus → connexion persistante + souscription. Même edge.

## Règles apprises (RESPECTER)
- JAMAIS passer `null` en paramètre → overload/no-op ([[null-arg-is-a-rule-violation]]).
- Pas de helper statique ; instance-passing ([[prefer-non-static-inner-keep-the-graph]]).
- Build de vérif : `flox activate -- ./mvnw -Pall-worlds clean package -Dmaven.build.cache.skipCache=true
  -DskipTests=false` (tests SEULEMENT avec `-Pall-worlds`).
- Commits orthogonaux (a20d2c7c renommage, 327c8c4c build-parent) : garder séparés au squash.
- Merge = squash dans design/pre-integration depuis la session pre-integration ([[merge-from-target-worktree]]).

Voir [[dbus-systemd-edge-spec-state]] [[felixframeworkextension-renamed-outofcontainer]]
[[dependency-analyze-gate-backlog]] [[external-edges-chantier-handoff]].
