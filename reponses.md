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

```
(à compléter lors de la partie 2)
```

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
