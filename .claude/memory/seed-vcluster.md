---
name: seed-vcluster
description: Prochain gros chantier — amorcer le vCluster gitops-mgmt + Flux (ex « layer 2 »/Stage B)
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

Après seed-master (Layer 1, validé sur infra réelle le 2026-06-03), le prochain chantier est **seed-vcluster** : amorcer le vCluster de management `gitops-mgmt` avec Flux dedans, par Pulumi.

Le user préfère le vocabulaire `seed-*` (seed-master, seed-vcluster) au « layer 1/2/3 » abstrait. « layer » n'existe que dans les docs (0 dans le code), donc peu coûteux à abandonner. On nomme le prochain chantier seed-vcluster sans (pour l'instant) renommer les docs existantes.

**Prérequis manquants identifiés** : vCluster operator ABSENT de la synthèse manifests (à créer), `com.pulumi:command` déclaré au BOM mais jamais utilisé (le bootstrap Flux en dépend). Flux operator + CAPI operator déjà présents.

Plan détaillé + arbitrages D1/D2/D3 : [.claude/seed-vcluster-plan.md](../../../../../private/var/lib/git/nxmatic/rke2lab/.claude/seed-vcluster-plan.md). Source (datée, vocabulaire pré-refactor) : docs/vcluster-implementation-plan.adoc.

Lié à [[refactor-pipeline-candidates]] et [[package-private-sweep]].
