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

*(à venir)*

---

## Partie 4 — Déployer sur Minikube

*(à venir)*

---

## Partie 5 — Exposer avec un Ingress

*(à venir)*

---

## Partie 6 — Casser pour comprendre

*(à venir)*

---

## Partie 7 — Questions de synthèse

*(à venir)*
