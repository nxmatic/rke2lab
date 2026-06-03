# Plan: Sweep package-private (retirer les `private` non essentiels)

> Créé le 2026-06-03. Pour une **session dédiée**. NE PAS faire en un seul `perl` global.

## Décision

Dans ce projet mono-développeur, `private` n'apporte rien de plus que la visibilité
package-private par défaut. Préférence du user : **package-private partout**, en
retirant le mot-clé `private` — SAUF les cas où `private` est légitime/obligatoire.

Référence : forme appliquée à
[DefaultManifestSynthesisService.java](../manifests/src/main/java/io/nxmatic/rke2lab/manifests/DefaultManifestSynthesisService.java)
(tous les `private` retirés, 2026-06-03). C'est l'exemplar de ce à quoi le reste doit ressembler.

## Ampleur mesurée (2026-06-03)

- **3131 occurrences** de `private` sur **316 fichiers** (src/main, tous modules).
- **398 constructeurs `private`** → à analyser un par un.
- **12 fichiers** avec interfaces contenant des `private`.

## Cas à PRÉSERVER (ne pas retirer `private`)

1. **Constructeurs de builder-enforcement** : CLAUDE.md impose « Builder → constructeur
   privé pour forcer son usage ». Si une classe a un builder + ctor privé, le ctor
   RESTE privé. (≈ parmi les 398 ctors privés.)
2. **Méthodes `private` d'interface** (Java 9+) : `private` y est OBLIGATOIRE. Retirer
   casse la compilation. (12 fichiers à interface.)
3. **Constructeurs de singleton** : `private Foo() {}` qui empêche l'instanciation.
4. **Tout `private` dont le retrait crée une collision/override** avec un membre
   homonyme package-private d'une autre classe du même package (révélé au build).

## Cas à RETIRER (`private` → package-private)

- Champs `private final X x;` (sauf cas 4).
- Méthodes utilitaires `private` de classe (helpers internes).
- Classes imbriquées `private` (deviennent package-private ou, si dans classe locale,
  perdent le modificateur — seul `final`/`abstract` permis sur classe locale).
- `private static final` constantes : OK à retirer (rester package-private).

## Méthode d'exécution (module par module, build entre chaque)

Le user lance les builds (`flox activate -- ./mvnw …`). Procéder par module pour pouvoir
compiler/valider à chaque étape, jamais à l'aveugle sur 3000+ sites.

Ordre suggéré (du plus simple/isolé au plus couplé) :

1. [ ] `manifests/` — module déjà travaillé. `./mvnw -pl :manifests verify`.
2. [ ] `netplan/`
3. [ ] `systemd-contract/`, `cdk8s-systemd/`
4. [ ] `sdks/incus`
5. [ ] `seed-master/` — le plus gros/couplé, en dernier.

Pour CHAQUE module :
1. Lister les ctors privés du module, classer builder-enforcement (préserver) vs autre.
2. Retirer `private` sur tout le reste (champs, méthodes, classes imbriquées).
3. Vérifier : pas de `private` d'interface retiré par erreur.
4. User compile le module → corriger collisions éventuelles → valider.
5. Commit par module : `refactor(<module>): package-private over private`.

## Vérifications anti-régression

- Après chaque module : `grep -rn 'private' <module>/src/main` ne doit montrer QUE les
  cas préservés (ctors builder, interface private, singleton).
- Build vert obligatoire avant module suivant.
- Aucune classe locale ne doit garder `private`/`static` sur ses membres-classes
  (erreur « Illegal modifier for the local class »).

## Mise à jour doc (en fin de sweep)

- [ ] CLAUDE.md : documenter « package-private par défaut, `private` réservé à
  l'enforcement (builder ctor, singleton) et aux méthodes d'interface ». Cohérence avec
  la règle builder existante.

## État

| Module | État | Notes |
|--------|------|-------|
| DefaultManifestSynthesisService (fichier) | ✅ fait | exemplar, 2026-06-03 |
| manifests/ (reste) | ☐ | premier module à traiter |
| netplan/ | ☐ | |
| systemd-contract, cdk8s-systemd | ☐ | |
| sdks/incus | ☐ | |
| seed-master/ | ☐ | en dernier (couplé) |
| CLAUDE.md update | ☐ | en fin de sweep |
