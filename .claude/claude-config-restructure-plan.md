# Plan: Restructuration de la configuration Claude

> Pour une **nouvelle session dédiée**. Créé le 2026-06-03 après session de refactor DomainRegistrars.

## Contexte

Après plusieurs sessions complexes (refactor terminologie, DomainRegistrars, patterns), on a identifié que :
- CLAUDE.md devient long (projet : ~350 lignes, global : ~400 lignes)
- Mélange de principes concis + exemples détaillés + historique de bugs
- Pas clair où mettre quoi : CLAUDE.md vs memory vs skills
- Besoin de garantir uniformité des patterns entre sessions

**Trigger** : "quelle est l'option qui me garantie que mon code sera uniforme et appliquera toujours les mêmes patterns ?"

## Audit de l'existant

### CLAUDE.md projet (rke2lab)

**Sections** (~350 lignes) :
1. Build & module layout (toolchain, Maven, JDK 25)
2. Fluent pipeline grammar (pointeur vers doc externe)
3. Code style (comments, no deprecation)
4. **Design principles** (~180 lignes) :
   - Immutability, builders, functional APIs
   - Instance-passing discipline (avec exemples détaillés)
   - ManifestDomainCatalog discipline (avec bug historique)
   - **NEW**: Lazy instantiation pattern (ajouté cette session)
   - **NEW**: Uniformity enforcement (ajouté cette session)
5. Documentation standards (~100 lignes)

**Observations** :
- Principes essentiels noyés dans exemples longs
- Historique de bugs (clusterApi May 31) utile mais verbose
- Discipline "instance-passing" : ~80 lignes dont 50 d'exemples

### CLAUDE.md global (~/.claude/)

**Sections** (~400 lignes) :
1. Language (user French, restate unclear English)
2. Code hygiene (no dead code)
3. Context window management (warn at 150k tokens)

**Observations** :
- Très concis, efficace
- Pas de duplication avec projet

### Auto-memory (/Users/nxmatic/.claude/projects/-private-var-lib-git-nxmatic-rke2lab/memory/)

**Fichiers** :
- `MEMORY.md` (index)
- `manifests-doc-consolidation.md` (projet)
- `terminology-refactor-state.md` (projet)

**Usage** : État de projets en cours, rappel pour prochaine session

### Settings.json (.claude/settings.json)

**Contenu** :
- Model: `us.anthropic.claude-opus-4-8[1m]`
- Hook PreCompact : génération checkpoint automatique

## Problèmes identifiés

### 1. CLAUDE.md trop long

**Symptôme** : Scroll pour trouver un principe, exemples détaillés occupent 60% du fichier

**Impact** : 
- Difficile à scanner rapidement
- Mélange "règles" (charge mentale) et "exemples" (référence)
- Nouveaux patterns s'empilent sans structure claire

### 2. Frontière floue CLAUDE.md / memory / skills

**Question non résolue** : Où mettre quoi ?

- **Patterns obligatoires** → CLAUDE.md (chargé automatiquement)
- **État de refactor en cours** → memory (contexte inter-sessions)
- **Outils d'audit** → skills (invoqués sur demande) ?

**Exemple** : Pipeline grammar est dans CLAUDE.md + pointeur vers doc externe. Est-ce le bon niveau de détail ?

### 3. Pas de mécanisme de vérification

**Symptôme** : On documente patterns mais rien ne force leur application

**Exemple cette session** :
- Premier workflow : ajoute no-arg + apply(Chart) override
- Deuxième itération : "non, on veut lazy instantiation"
- Troisième itération : "non, pas de legacy, uniformité absolue"

**Question** : Aurait-on pu éviter 2 itérations avec meilleure config ?

## Propositions de réorganisation

### Option A : CLAUDE.md modulaire

Découper en fichiers séparés :

```
.claude/
  CLAUDE.md              # Index + pointeurs
  PRINCIPLES.md          # Principes concis (20-30 lignes)
  PATTERNS.md            # Patterns réutilisables (lazy, pipeline, catalogs)
  CONVENTIONS.md         # Code style, naming, toolchain
  REFACTORING.md         # Checklist refactor, uniformity enforcement
  EXAMPLES.md            # Exemples détaillés, bug historiques
```

**Avantage** : Séparation claire, scan rapide
**Inconvénient** : Plusieurs fichiers à maintenir, risque duplication

### Option B : CLAUDE.md compact + docs/ détaillées

Garder CLAUDE.md concis (~100 lignes de règles pures), déplacer exemples vers `docs/` :

```
CLAUDE.md                           # Règles + pointeurs vers docs
docs/patterns/
  lazy-instantiation.adoc
  instance-passing-discipline.adoc
  uniformity-enforcement.adoc
```

**Avantage** : CLAUDE.md léger, exemples avec C4 diagrams dans docs/
**Inconvénient** : Deux endroits à chercher (config vs documentation)

### Option C : CLAUDE.md actuel + skill /audit-patterns

Garder structure actuelle, ajouter skill qui scanne le code pour violations :

```bash
/audit-patterns
# → grep méthodes 3+ boolean params sans pipeline
# → grep hardcoded domain IDs vs catalog
# → grep instances null scope
# → grep patterns non-uniformes
```

**Avantage** : Détection proactive, pas de réorganisation lourde
**Inconvénient** : Ne résout pas le problème "CLAUDE.md trop long"

### Option D : Hybride (recommandé)

**CLAUDE.md** : Principes concis (~150 lignes max)
- Chaque principe : 1 paragraphe why + 1 exemple minimal + pointeur vers doc détaillée

**docs/patterns/** : Exemples complets avec C4 diagrams
- Suivre standard docs/ existant (AsciiDoc, Mermaid)
- Cross-référencés depuis CLAUDE.md

**Skill /audit-patterns** : Vérification optionnelle
- Invoqué manuellement ou en pre-commit hook

**Memory** : Reste pour état de projets en cours uniquement

## Étapes suggérées pour nouvelle session

### Phase 1 : Audit détaillé (~30 min)

1. Lire CLAUDE.md ligne par ligne, catégoriser chaque section :
   - [ ] Principe essentiel (doit rester)
   - [ ] Exemple détaillé (peut aller en docs/)
   - [ ] Historique bug (peut aller en docs/)
   - [ ] Redondant (à supprimer)

2. Identifier patterns manquants :
   - [ ] Fluent pipeline : trigger explicite (3+ boolean params)
   - [ ] Builder pattern : quand obligatoire vs optionnel
   - [ ] Record conversion : règles précises

3. Mesurer :
   - [ ] Lignes CLAUDE.md avant/après
   - [ ] Nombre de principes vs exemples
   - [ ] Charge cognitive (peut-on tenir en mémoire ?)

### Phase 2 : Proposition de structure (~20 min)

Basé sur audit, choisir Option A/B/C/D et détailler :

- [ ] Nouvelle arborescence fichiers
- [ ] Quels contenus vont où
- [ ] Impact sur sessions futures (plus rapide à scanner ?)

### Phase 3 : Implémentation (~1-2h selon ampleur)

Si Option D (hybride) :

1. [ ] Créer `docs/patterns/` avec structure
2. [ ] Extraire exemples détaillés de CLAUDE.md → docs/patterns/*.adoc
3. [ ] Réduire CLAUDE.md à principes concis + pointeurs
4. [ ] Créer skill `/audit-patterns` (optionnel)
5. [ ] Documenter le système lui-même (meta-doc)

### Phase 4 : Test (~15 min)

- [ ] Simuler nouveau refactor : est-ce que CLAUDE.md suffit pour décisions ?
- [ ] Mesurer temps pour trouver un principe (avant vs après)
- [ ] Vérifier que rien d'essentiel n'a été perdu

## Critères de succès

La restructuration est réussie si :

✅ **CLAUDE.md tient en un écran** (~150 lignes max) sans scroll pour principes essentiels

✅ **Temps de décision réduit** : trouver "puis-je utiliser pattern X ?" en <30 secondes

✅ **Uniformité garantie** : patterns documentés = patterns appliqués (via audit ou checklist)

✅ **Maintenable long terme** : ajout nouveau pattern = 10 lignes CLAUDE.md + doc détaillée, pas 80 lignes inline

✅ **Pas de perte d'information** : historiques bugs, exemples détaillés existent quelque part (docs/)

## Notes pour la session

**Contraintes** :
- User est French native, English développé — garder clarifications si phrase ambiguë
- Projet complexe (Incus, K8s, Cluster API, GitOps, systemd) — besoin context recovery rapide
- Single developer — pas de backward compat, refactor atomic

**Tone** :
- Pas d'emojis sauf demande explicite
- Concis, pas de narration interne
- Updates courts pendant workflows

**Références utiles** :
- Session actuelle : refactor DomainRegistrars, lazy pattern, uniformity
- `docs/bootstrap-identity-provider.adoc` : standard qualité doc
- `.claude/terminology-refactor-plan.md` : exemple plan détaillé

## Risques

⚠️ **Sur-ingénierie** : Découper trop finement → complexité accrue
→ Mitigation : Commencer minimaliste (Option B ou D), itérer si besoin

⚠️ **Perte d'info** : Déplacer exemples → oubliés lors décisions
→ Mitigation : Pointeurs explicites CLAUDE.md → docs, search works

⚠️ **Maintenance overhead** : Plus de fichiers = plus à maintenir
→ Mitigation: Structure claire, cross-refs bidirectionnels

## Métriques à suivre

Avant restructure :
- CLAUDE.md projet : 350 lignes
- Principes essentiels : ~15 (noyés dans 335 lignes)
- Temps pour trouver principe : ~1-2 min (scroll + lecture)

Après restructure (cible) :
- CLAUDE.md projet : ~150 lignes
- Principes essentiels : ~15 (tous visibles en 1 écran)
- Temps pour trouver principe : <30s (scan visuel)
