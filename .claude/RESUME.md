# RESUME — world-gateway 2D, mid-execution, PIVOT to records-as-contract (2026-06-30)

AVANT TOUT : lis `.claude/hub/instructions.md` § "Progress narration" et applique-la.
Branche : `feature/cluster-edge`. Lis EN ENTIER, dans l'ordre :
1. `.claude/memory/world-gateway-2d-execution-state.md` — l'état 2D + LE PIVOT + LA CARTO (ne PAS re-scanner).
2. `.claude/memory/world-gateway-2c-complete-2d-designed-state.md` — le contexte 2D d'origine.
3. Le plan : `docs/superpowers/plans/2026-06-30-world-gateway-2d-schema-contract.md` (zone-0 fait ; le
   concord ASM est SUPERSEDED par le pivot).

## Où on en est — EXACTEMENT

2D en exécution subagent-driven. **Zone-0 DONE** (3 tasks, commits jusqu'à `308da3ad`) :
networknt pinné (build-tooling), `gateway-document-codec` (pattern flat∧nesté), gate `SCHEMA_CONCORD`
câblé + dormant (0/0, reactor vert).

**MAIS** une carto de structure des 6 documents a INVALIDÉ l'approche concord-par-ASM (4 traducteurs
read-A-write-B ; `intervention` n'a aucun FIELD_* ; les docs nichent). **DÉCISION USER (à NE PAS
reverter)** : monter d'un niveau — **record-par-Document = le contrat, le schéma JSON est GÉNÉRÉ du
record**, automatisé par scan. Ça fusionne le backlog "éliminer les FIELD_*" DANS 2D.

## PROCHAINE ÉTAPE (précise)

Design ÉCRIT + **USER GO**. Doc : `docs/superpowers/plans/2026-06-30-world-gateway-2d-records-as-contract-design.md`.
Décisions tranchées (cf. `world-gateway-2d-execution-state.md` § DESIGN WRITTEN + USER GO) :
wire-records dans le seam `world.gateway.port` (à plat), schéma GÉNÉRÉ build-time des `RecordComponents`
(générateur dans `maven-embed-staging-ext`), nesting via records imbriqués, gate réécrit
(construction-par-record-only, l'ancien `CoordinateFieldUsage` ASM est SUPPRIMÉ), blobs opaques =
`Map<String,Object>`. Wire-record = source-schéma + unité de sérialisation ; record riche garde `toWire()`.

EXÉCUTION : T4 (scaffolding seam + générateur + gate WARN) INLINE. T5-T9 (migration par-coordinate)
subagent-driven. T10 = supprimer le bloc FIELD_* + flip WARN→ERROR. Détail des tasks dans la mémoire d'état.

## Build (user-confirmé)

`flox activate -- ./mvnw package -Pall-worlds -Dmaven.build.cache.skipCache=true -DskipTests=false`
— SKIP le cache (pas `enabled=false`), pas de `clean` sauf stale suspecté. Extension = two-phase dance.

## Backlogs / notes annexes

- Régression jsr310/slf4j RÉSOLUE (commits ae46278b+0185b32a, pulumi preview vert) — clos.
- Une régression BDD seed-master PRÉ-EXISTANTE (test-compile, démasquée cache-off) = autre chantier,
  PAS nous — bloquera le gate full-reactor *avec tests* en fin de branche.
- `RESUME.md` + le wrapper `.claude/hub/bin/claude-config-home-wrapper.sh` restent LOCAUX (non commités).
