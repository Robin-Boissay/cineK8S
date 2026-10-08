# Examen CinéK8s — BOISSAY Robin

## Partie 1 — Comprendre le code

### Q1.1
- **Propriété Spring lue par `MovieClient` :** `movie.url` (injectée via `@Value("${movie.url}")` dans le constructeur de `MovieClient`).
- **Variable d'environnement de surcharge :** `MOVIE_URL`. Spring Boot utilise le mécanisme de *relaxed binding*, qui convertit automatiquement une variable d'environnement en majuscules avec underscores (`MOVIE_URL`) en clé de propriété en minuscules avec des points (`movie.url`).

---

### Q1.2
Codes HTTP retournés par `ticket-service` (`TicketController`) :
- **(a) Le film demandé n'existe pas :** `422 Unprocessable Entity` (déclenché lorsque `movieClient.findMovie(request.movieId())` renvoie un `Optional.empty()`).
- **(b) Il reste moins de places que demandé :** `409 Conflict` (déclenché par la condition `movie.seats() < request.seats()`).
- **(c) `movie-service` ne répond pas du tout :** `503 Service Unavailable` (déclenché lors de la capture de l'exception `ResourceAccessException` levée lors de l'appel HTTP vers `movie-service`).

---

### Q1.3
**Ligne complétée dans `ticket-service/src/main/resources/application.yaml` :**
```yaml
      group:
        readiness:
          include: readinessState,movie
```
*(Le nom de composant `movie` correspond au nom de bean déclaré dans l'annotation `@Component("movie")` de `MovieHealthIndicator`.)*

**Explication :**
Si cette dépendance externe vers `movie-service` était placée dans la **liveness probe**, toute coupure de `movie-service` ferait échouer le test de vivacité de `ticket-service` et pousserait le kubelet à redémarrer inutilement les conteneurs `ticket-service` en boucle (cascading failure / crash loop), alors que le problème est extérieur.
Dans la **readiness probe**, l'échec de la dépendance externe retire simplement les Pods `ticket-service` des Endpoints du Service sans les redémarrer, protégeant ainsi les utilisateurs d'erreurs en attendant que `movie-service` redevienne joignable.

---

### Q1.4

| Endpoint | Probe(s) Kubernetes qui l'utilisent | Conséquence d'un **échec** de la probe |
|---|---|---|
| `/actuator/health/liveness` | `livenessProbe` (et éventuellement `startupProbe`) | Le kubelet considère le conteneur comme défaillant/bloqué et **redémarre le conteneur** (incrémente le compteur de RESTARTS). |
| `/actuator/health/readiness` | `readinessProbe` | Le Pod passe à l'état non prêt (`0/1 Ready`) et son adresse IP est **retirée des Endpoints du Service Kubernetes**, coupant l'envoi de trafic vers ce Pod sans le redémarrer. |

**Utilité de `server.shutdown: graceful` lors d'un rolling update :**
Lors d'un rolling update, le graceful shutdown permet au conteneur en cours d'arrêt de refuser les nouvelles requêtes tout en laissant un délai de grâce pour achever l'exécution des requêtes HTTP en cours, évitant ainsi de couper des transactions clientes (ce qui prévient les erreurs 502 ou interruptions de connexion).

---

## Partie 2 — Tester en local, sans Kubernetes

### 2.1 — Lancer les deux services

Sortie de la réservation (`POST /api/tickets`) :
```bash
curl -s -X POST localhost:8082/api/tickets \
  -H 'Content-Type: application/json' \
  -d '{"movieId":2,"seats":3}' | jq
```
```json
{
  "id": 1,
  "movieId": 2,
  "movieTitle": "Le Seigneur des Pods",
  "seats": 3,
  "total": 36.00,
  "createdAt": "2026-10-08T09:05:58.842185084Z"
}
```

Sortie de la readiness (`GET /actuator/health/readiness`) :
```bash
curl -s localhost:8082/actuator/health/readiness | jq
```
```json
{
  "status": "UP",
  "components": {
    "movie": {
      "status": "UP"
    },
    "readinessState": {
      "status": "UP"
    }
  }
}
```

---

### 2.2 — Couper `movie-service`

Sortie de la readiness avec `movie-service` arrêté :
```bash
curl -s localhost:8082/actuator/health/readiness | jq
```
```json
{
  "status": "DOWN",
  "components": {
    "movie": {
      "status": "DOWN",
      "details": {
        "error": "I/O error on GET request for \"http://localhost:8080/actuator/health/liveness\": null"
      }
    },
    "readinessState": {
      "status": "UP"
    }
  }
}
```

Sortie de la liveness :
```bash
curl -s localhost:8082/actuator/health/liveness | jq .status
```
```json
"UP"
```

Code HTTP lors d'une tentative de réservation :
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8082/api/tickets \
  -H 'Content-Type: application/json' -d '{"movieId":2,"seats":3}'
```
```text
503
```

---

### 2.3 — Questions

> **Q2.1** — Pourquoi lance-t-on `ticket-service` avec `SERVER_PORT=8082` plutôt qu'en modifiant `application.yaml` ? Quel mécanisme Spring Boot rend cela possible ?

- **Pourquoi :** Cela permet d'éviter un conflit de port en local avec `movie-service` (qui écoute sur le port 8080) sans modifier les fichiers de code source ou de configuration versionnés (`application.yaml`). Cela respecte les principes *12-Factor App* (séparation configuration / code) et garantit que le fichier `application.yaml` reste prêt pour la conteneurisation et Kubernetes (où chaque service tourne dans son propre Pod / conteneur isolé et écoute sur le port standard 8080).
- **Mécanisme Spring Boot :** L'**externalisation de configuration** (*Externalized Configuration*) combinée au **relaxed binding**. Les variables d'environnement système ont une priorité plus élevée que les fichiers `application.yaml` dans l'ordre d'évaluation de Spring Boot, et la variable `SERVER_PORT` est automatiquement liée à la propriété `server.port`.

---

> **Q2.2** — Dans l'étape 2.2, la liveness est restée `UP` alors que la readiness est passée `DOWN`. Pourquoi est-ce exactement le comportement voulu ?

- **Liveness à `UP` :** La liveness indique si le processus applicatif interne est en vie et sain (JVM active, pas de deadlock, thread principal réactif). Puisque le processus `ticket-service` fonctionne parfaitement, il ne doit surtout pas être tué ni redémarré (un redémarrage ne réparerait pas `movie-service` et créerait une boucle de redémarrage inutile).
- **Readiness à `DOWN` :** La readiness indique si le service est prêt à traiter convenablement les requêtes des clients. Puisque la dépendance indispensable `movie-service` est indisponible, `ticket-service` ne peut pas honorer les réservations. Passer à `DOWN` permet (dans Kubernetes) de retirer immédiatement le Pod des cibles du Service afin qu'aucun trafic ne lui soit acheminé tant que la dépendance n'est pas rétablie.

---

## Partie 3 — Conteneuriser

### 3.3 — Questions

> **Q3.1** — Pourquoi copie-t-on `pom.xml` **avant** `src/` dans le Dockerfile ? Que se passe-t-il quand vous ne modifiez qu'une ligne de Java ?

- **Pourquoi :** Docker met en cache le résultat de chaque instruction (`layer cache`). Les dépendances Maven déclarées dans le `pom.xml` changent beaucoup moins fréquemment que le code métier dans `src/`. En copiant d'abord le `pom.xml` seul et en exécutant `mvn dependency:go-offline`, on crée une couche de cache contenant toutes les dépendances téléchargées.
- **Lors d'une modification de code :** Si l'on modifie seulement une ligne de code Java dans `src/`, la couche du `pom.xml` et le téléchargement des dépendances restent valides en cache. Docker réutilise ce cache et ne réexécute que la copie de `src/` et le `mvn package -DskipTests`, ce qui accélère considérablement le build (quelques secondes au lieu de plusieurs minutes à retélécharger toutes les dépendances).

---

> **Q3.2** — Pourquoi `-XX:MaxRAMPercentage=75` est-il préférable à `-Xmx512m` dans un conteneur ?

- `-Xmx512m` fixe une valeur maximale absolue en dur. Si l'on change les limites de mémoire du conteneur (par exemple à 256Mi dans Kubernetes), la JVM tentera d'allouer plus de mémoire que la limite imposée par le cgroup du conteneur, provoquant un arrêt brutal par le kernel Linux (`OOMKilled`). À l'inverse, si on alloue 2Go au conteneur, `-Xmx512m` n'en exploitera qu'un quart sans s'adapter.
- `-XX:MaxRAMPercentage=75` rend la JVM adaptative et consciente des limites du conteneur (*container-aware*) : elle calcule dynamiquement son heap maximum à 75 % de la limite mémoire allouée au conteneur (par Docker ou Kubernetes). Les 25 % restants sont automatiquement réservés pour la mémoire hors-heap (Metaspace, threads stack, code cache, buffers natifs), évitant ainsi tout dépassement fatal de la mémoire totale du conteneur.

---

> **Q3.3** — Compose a `depends_on: condition: service_healthy`. Kubernetes n'a **pas** d'équivalent direct. Que se passe-t-il, dans Kubernetes, si les Pods `ticket` démarrent **avant** les Pods `movie` ?

- Dans Kubernetes, les Pods démarrent de manière indépendante et asynchrone. Si les Pods `ticket` démarrent en premier :
  1. Le conteneur `ticket` se lance et sa **liveness probe** est validée dès que Spring Boot est vivant en interne (aucun redémarrage en boucle).
  2. En revanche, sa **readiness probe** échoue car `MovieHealthIndicator` ne parvient pas à joindre `http://movie:8080/actuator/health/liveness`.
  3. Tant que `movie` n'est pas prêt, le Pod `ticket` reste en statut `0/1 Ready` et Kubernetes refuse de l'ajouter aux Endpoints de son Service, protégeant les clients en ne leur acheminant aucun trafic.
  4. Dès que les Pods `movie` sont prêts et que le Service `movie` répond, la probe de `ticket` passe automatiquement au vert (`1/1 Ready`), et le trafic commence à être routé normalement.

---

## Partie 4 — Déployer sur Minikube

### 4.4 — Vérifications

Sortie de `kubectl get pods` :
```bash
kubectl get pods
```
```text
NAME                      READY   STATUS    RESTARTS   AGE
movie-59684459f4-bjg7z    1/1     Running   0          78s
movie-59684459f4-xddfz    1/1     Running   0          78s
ticket-66d95c98b6-729wj   1/1     Running   0          78s
ticket-66d95c98b6-g68rh   1/1     Running   0          78s
```

Sortie de `kubectl get endpoints` :
```bash
kubectl get endpoints movie ticket
```
```text
NAME     ENDPOINTS                           AGE
movie    10.244.0.24:8080,10.244.0.26:8080   102s
ticket   10.244.0.25:8080,10.244.0.27:8080   102s
```

Réservation créée (`POST /api/tickets` via port-forward) :
```bash
curl -s -X POST localhost:8082/api/tickets -H 'Content-Type: application/json' \
  -d '{"movieId":2,"seats":2}' | jq
```
```json
{
  "id": 1,
  "movieId": 2,
  "movieTitle": "Le Seigneur des Pods",
  "seats": 2,
  "total": 24.00,
  "createdAt": "2026-10-08T10:14:10.666670672Z"
}
```

---

### 4.5 — Questions

> **Q4.1** — `kubectl apply -f k8s/` traite les fichiers dans quel ordre ? En quoi les préfixes `00-`, `10-`, `20-`… sont-ils utiles ici ?

- **Ordre de traitement :** `kubectl apply -f k8s/` parcourt les fichiers du répertoire dans l'**ordre alphabétique** (lexicographique) de leurs noms.
- **Utilité des préfixes :** Les préfixes numériques imposent un ordre de création déterministe respectant les dépendances entre ressources :
  1. `00-namespace.yaml` : crée d'abord le Namespace `cinema-exam`, indispensable car toutes les autres ressources y sont rattachées.
  2. `10-config.yaml` : crée les ConfigMaps avant le déploiement des applications qui en ont besoin au démarrage.
  3. `20-movie.yaml` puis `30-ticket.yaml` : déploie les Pods et Services une fois les prérequis (namespace, configs) déjà en place dans le cluster.

---

> **Q4.2** — Pendant environ 30 s après le déploiement, les Pods sont `0/1`. Quelle probe est responsable ? Est-ce une anomalie ?

- **Probe responsable :** C'est la **`startupProbe`** (couplée à la `readinessProbe`).
- **Anomalie ou normal :** Ce n'est **absolument pas une anomalie**. Une application Spring Boot prend généralement 15 à 30 secondes pour initialiser la JVM, charger le contexte applicatif et démarrer son serveur web Tomcat. Durant cette phase d'amorçage, la `startupProbe` effectue des tests (toutes les 2 s) et suspend la `livenessProbe` pour ne pas tuer le conteneur prématurément. Tant que la startupProbe n'a pas réussi et que la readinessProbe n'a pas validé que le service est prêt à recevoir du trafic, le Pod reste temporairement en `0/1 Ready`.

---

> **Q4.3** — Que se passerait-il avec `imagePullPolicy: Always` sur ces images ? Pourquoi ?

- Avec `imagePullPolicy: Always`, le kubelet force la recherche et le téléchargement de l'image depuis un registre distant de conteneurs (par exemple Docker Hub) à chaque création de conteneur, en ignorant le cache local du nœud.
- Comme `movie-service:1.0.0` et `ticket-service:1.0.0` sont des images locales (construites ou chargées directement dans Minikube) et n'existent pas sur un registre public externe, le pull échouerait systématiquement avec une erreur **`ErrImagePull`** puis **`ImagePullBackOff`**, empêchant les Pods de démarrer.
- La valeur `IfNotPresent` permet d'utiliser l'image déjà présente localement dans le runtime de Minikube sans tenter d'aller la chercher sur Internet.

---

## Partie 5 — Exposer avec un Ingress

*(à venir)*

---

## Partie 6 — Casser pour comprendre

*(à venir)*

---

## Partie 7 — Questions de synthèse

*(à venir)*
