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

### 5.3 — Tests via l'Ingress

Liste des films (`GET http://cinema.local/api/movies`) :
```bash
curl -s http://cinema.local/api/movies | jq '.[].title'
```
```text
"Pod Fiction"
"Le Seigneur des Pods"
"Docker Wars"
"Rollback to the Future"
```

Réservation de places (`POST http://cinema.local/api/tickets`) :
```bash
curl -s -X POST http://cinema.local/api/tickets -H 'Content-Type: application/json' \
  -d '{"movieId":3,"seats":10}' | jq
```
```json
{
  "id": 2,
  "movieId": 3,
  "movieTitle": "Docker Wars",
  "seats": 10,
  "total": 90.00,
  "createdAt": "2026-10-08T10:17:50.519331987Z"
}
```

Load-balancing observé sur `whoami` :
```bash
for i in $(seq 1 6); do curl -s http://cinema.local/api/movies/whoami | jq -r .hostname; done
```
```text
movie-59684459f4-xddfz
movie-59684459f4-xddfz
movie-59684459f4-bjg7z
movie-59684459f4-bjg7z
movie-59684459f4-xddfz
movie-59684459f4-xddfz
```

Test d'accès à Actuator via Ingress :
```bash
curl -s -o /dev/null -w '%{http_code}\n' http://cinema.local/actuator/health
```
```text
404
```

---

### 5.4 — Questions

> **Q5.1** — Combien de Pods `movie` distincts ont répondu dans la boucle ? Quel objet Kubernetes répartit la charge entre eux ?

- **Nombre de Pods :** Deux Pods distincts ont répondu (`movie-59684459f4-xddfz` et `movie-59684459f4-bjg7z`), conformément aux 2 réplicas configurés.
- **Objet Kubernetes :** C'est le **Service Kubernetes** (`movie`) en combinaison avec l'**Ingress Controller** (`ingress-nginx`). L'Ingress Controller route le trafic HTTP externe vers le Service, et les mécanismes de routage de Kubernetes (Endpoints / EndpointSlices gérés par kube-proxy avec iptables/IPVS) répartissent la charge entre les Pods cibles.

---

> **Q5.2** — Que se passerait-il pour `GET /api/movies/1` si vous aviez mis `pathType: Exact` sur `/api/movies` ?

- Avec `pathType: Exact`, l'Ingress ne transmet que les requêtes correspondant strictement et exactement à l'URI `/api/movies`.
- Une requête comme `GET /api/movies/1` (ou `/api/movies/whoami`) ne correspondrait plus à cette règle exacte. L'Ingress Controller NGINX ne trouverait aucune route correspondante et renverrait un code d'erreur **`404 Not Found`**.
- La valeur `pathType: Prefix` est donc nécessaire pour router l'ensemble des sous-chemins débutant par `/api/movies`.

---

> **Q5.3** — Quel code HTTP obtenez-vous pour `/actuator/health` via `cinema.local` ? Est-ce souhaitable ? Pourquoi ?

- **Code HTTP obtenu :** `404 Not Found`.
- **Est-ce souhaitable :** **Oui, c'est tout à fait souhaitable et recommandé.**
- **Pourquoi :** Les endpoints Spring Boot Actuator (`/actuator/**`) exposent des données internes sensibles (santé détaillée, métriques, variables d'environnement, configuration du runtime). Ils sont réservés à l'infrastructure interne du cluster (les probes Kubernetes du kubelet, les systèmes de métriques Prometheus, etc.) et ne doivent **jamais** être accessibles publiquement depuis l'extérieur via l'Ingress, afin de protéger l'application contre les fuites d'informations et les risques d'attaques par déni de service.

---

## Partie 6 — Casser pour comprendre

### 6.1 — Le service `movie` disparaît

#### Prédictions (avant commande) :
- **(a) `READY` et `RESTARTS` des Pods `ticket` après 30 s :** `READY` passera à `0/1` et `RESTARTS` restera à `0`.
- **(b) Contenu de `kubectl get endpoints ticket` :** Aucun endpoint actif (`<none>`).
- **(c) Code HTTP de `GET http://cinema.local/api/tickets` :** `503 Service Temporarily Unavailable` (renvoyé par l'Ingress NGINX car aucun backend n'est prêt).
- **(d) Statut de la liveness de `ticket` :** `"UP"` (la santé interne de Spring Boot reste saine).

#### Observations réelles :

Sortie de `kubectl get pods` après coupure de `movie` :
```bash
kubectl get pods
```
```text
NAME                      READY   STATUS    RESTARTS   AGE
ticket-66d95c98b6-729wj   0/1     Running   0          8m26s
ticket-66d95c98b6-g68rh   0/1     Running   0          8m26s
```

Sortie de `kubectl get endpoints ticket` :
```bash
kubectl get endpoints ticket
```
```text
NAME     ENDPOINTS   AGE
ticket               8m38s
```

Requête via Ingress :
```bash
curl -si http://cinema.local/api/tickets | head -1
```
```text
HTTP/1.1 503 Service Temporarily Unavailable
```

Événements constatés sur les Pods `ticket` :
```bash
kubectl describe pod -l app=ticket | grep -i "probe failed"
```
```text
Warning  Unhealthy  kubelet  Readiness probe failed: HTTP probe failed with statuscode: 503
```

---

#### Q6.1 — Explication en 4 étapes et analyse du statut RESTARTS :

**Déroulement en 4 étapes :**
1. **Suppression des Pods `movie` :** Le scaling de `deploy/movie` à 0 supprime tous les Pods `movie`. Les requêtes vers `http://movie:8080` échouent désormais (connexion refusée ou résolution DNS sans cible).
2. **Échec de la readiness de `ticket` :** Le kubelet effectue périodiquement la `readinessProbe` sur `/actuator/health/readiness`. Le composant `MovieHealthIndicator` échoue à joindre `movie-service` et retourne `status: DOWN` (HTTP 503). Après 3 échecs consécutifs (`failureThreshold: 3`), le kubelet marque les Pods `ticket` en non prêts (`READY: 0/1`).
3. **Retrait des Endpoints :** Le contrôleur d'Endpoints Kubernetes détecte que les Pods `ticket` sont `NotReady` et retire immédiatement leurs adresses IP de la liste des Endpoints du Service `ticket`.
4. **Réponse 503 de l'Ingress :** Lors d'un appel vers `http://cinema.local/api/tickets`, l'Ingress Controller NGINX cherche un Pod sain dans le pool d'Endpoints du Service `ticket`. Constatant que la liste est vide, il renvoie immédiatement un code HTTP **`503 Service Temporarily Unavailable`**.

**Pourquoi `RESTARTS` est resté à `0` :**
La dépendance vers `movie-service` est surveillée exclusivement par la **`readinessProbe`** et non par la `livenessProbe`. La liveness probe a continué à sonder `/actuator/health/liveness`, qui vérifie uniquement la santé interne de la JVM et du contexte Spring Boot de `ticket-service` (resté `UP`). Comme la liveness ne détectait aucune anomalie interne, le kubelet n'avait aucune raison de tuer le conteneur, laissant le compteur `RESTARTS` à 0 et évitant un redémarrage en boucle inutile.

---

### 6.2 — Mission dépannage (`broken/ticket-debug.yaml`)

Tableau de diagnostic et résolution des 3 erreurs successives :

| # | Statut observé | Commande de diagnostic | Cause exacte | Correction apportée |
|---|----------------|------------------------|--------------|---------------------|
| 1 | `ErrImagePull` / `ImagePullBackOff` | `kubectl describe pod -l app=ticket-debug` | `imagePullPolicy: Always` force le kubelet à chercher l'image locale `ticket-service:1.0.0` sur le registre distant Docker Hub, où elle n'existe pas. | Remplacé `imagePullPolicy: Always` par `imagePullPolicy: IfNotPresent` dans `broken/ticket-debug.yaml`. |
| 2 | `CreateContainerConfigError` | `kubectl describe pod -l app=ticket-debug` et `kubectl get cm` | `Error: configmap "ticket-configmap" not found`. Le manifest référençait une ConfigMap inexistante au lieu de `ticket-config`. | Remplacé `name: ticket-configmap` par `name: ticket-config` dans `envFrom.configMapRef`. |
| 3 | `Running` mais `0/1 Ready` indéfiniment | `kubectl describe pod -l app=ticket-debug` | `Readiness probe failed: ... dial tcp ...:8081: connect: connection refused`. La probe interrogeait le port 8081 alors que Tomcat écoute sur 8080. | Remplacé `port: 8081` par `port: 8080` dans `readinessProbe.httpGet.port`. |

Après ces 3 corrections successives, le Pod `ticket-debug` est bien passé en statut **`1/1 Running`**.

---

### 6.3 — Changer la configuration sans rebuild

> **Q6.3** — Pourquoi la modification n'a-t-elle **pas** été prise en compte immédiatement ? Qu'est-ce qui l'a rendue effective ?

- **Pourquoi pas immédiatement :**
  Les valeurs de la ConfigMap sont injectées sous forme de **variables d'environnement** au moment de la création du conteneur (`envFrom: configMapRef`). Sous Linux et Kubernetes, les variables d'environnement d'un processus en cours d'exécution sont immuables. Modifier la ConfigMap dans Kubernetes met à jour la ressource dans etcd, mais n'altère en rien l'environnement du processus Java déjà en cours d'exécution dans les conteneurs existants.
- **Ce qui l'a rendue effective :**
  La commande `kubectl rollout restart deploy/movie` a déclenché un redémarrage progressif (*rolling update*) du déploiement. De nouveaux Pods ont été instanciés ; au moment de leur création, ils ont lu la version actualisée de la ConfigMap, injectant `MOVIE_ENVIRONMENT=production` dans leur environnement et dans le contexte Spring Boot au démarrage.

---

## Partie 7 — Questions de synthèse

> **Q7.1** — Décrivez ce qui se passe, étape par étape, quand un Pod `ticket` exécute `GET http://movie:8080/api/movies/1` : qui résout le nom `movie` ? en quoi ? comment la requête atteint-elle *un* Pod `movie` précis ?

1. **Résolution DNS :** Le Pod `ticket` interroge le DNS interne du cluster (**CoreDNS**, configuré via `/etc/resolv.conf`). CoreDNS résout le nom court `movie` (complété automatiquement en FQDN `movie.cinema-exam.svc.cluster.local`) en l'adresse IP virtuelle (**ClusterIP**) du Service `movie`.
2. **Routage et équilibrage :** Lorsque le paquet TCP est émis vers cette ClusterIP sur le port 8080, le composant **kube-proxy** (via les règles `iptables` ou `IPVS` au niveau du noyau Linux) intercepte le trafic et sélectionne l'adresse IP privée de l'un des Pods sains listés dans les `Endpoints` du Service `movie` en effectuant un DNAT (*Destination NAT*).
3. **Acheminement au Pod :** Le paquet est ensuite routé via le réseau CNI du cluster directement vers l'interface réseau du Pod `movie` sélectionné, qui traite la requête HTTP et renvoie les données.

---

> **Q7.2** — Créez 4 réservations puis lancez plusieurs fois `curl -s http://cinema.local/api/tickets | jq length`. Le nombre varie d'un appel à l'autre. **Pourquoi ?** Que se passe-t-il si vous supprimez les Pods `ticket` ? Quelle est la solution **architecturale** (pas une astuce) ?

- **Pourquoi le nombre varie :** Le contrôleur `TicketController` stocke l'état des réservations dans une liste locale **en mémoire vive** propre à chaque JVM (`CopyOnWriteArrayList`). Comme il y a 2 réplicas de `ticket` et que l'Ingress/Service distribue les requêtes entre eux, chaque Pod ne possède et ne renvoie que les tickets qu'il a lui-même enregistrés.
- **Si les Pods sont supprimés :** La mémoire vive étant volatile, **toutes les réservations sont irrémédiablement perdues** lors de l'arrêt ou du redémarrage d'un Pod.
- **Solution architecturale :** Rendre le service **stateless** (sans état en mémoire) en déportant la persistance des données vers une source de stockage externe partagée (base de données relationnelle comme PostgreSQL/MySQL, ou base clé-valeur / cache distribué comme Redis), accessible par tous les réplicas de manière cohérente.

---

> **Q7.3** — Supprimez un Pod `movie` à la main (`kubectl delete pod ...`). Que constatez-vous ? Qu'auriez-vous perdu si vous aviez déployé un `kind: Pod` « nu » à la place d'un `Deployment` ?

- **Constat :** Dès la suppression du Pod, un nouveau Pod `movie` est **instantanément recréé** par le `ReplicaSet` afin de maintenir en permanence l'état désiré (`replicas: 2`).
- **Ce qu'on aurait perdu avec un Pod nu (`kind: Pod`) :**
  Un Pod nu n'est managé par aucun contrôleur. S'il est supprimé, s'il crashe ou si son nœud tombe en panne, il est **définitivement perdu** (*aucun self-healing*). De plus, on perdrait :
  - La gestion déclarative du nombre de réplicas et le scaling (`kubectl scale`).
  - Les stratégies de mise à jour sans coupure de service (`RollingUpdate`).
  - L'historique des déploiements et la possibilité de retour arrière immédiat (`rollout undo`).

---

## ⭐ Bonus — Durcir et fiabiliser

### ⭐ B1 — Durcir le Deployment `movie`

Le manifest `k8s/20-movie.yaml` a été enrichi d'un `securityContext` strict au niveau du conteneur :
- `runAsNonRoot: true` et `runAsUser: 10001` (UID spring).
- `allowPrivilegeEscalation: false` (interdiction d'élévation de privilèges).
- `capabilities.drop: ["ALL"]` (suppression de toutes les capabilities Linux).
- `readOnlyRootFilesystem: true` (système de fichiers racine en lecture seule).
- Ajout d'un volume `emptyDir` monté sur `/tmp` pour permettre à Tomcat d'écrire ses fichiers temporaires nécessaires.

#### Vérifications :
- Vérification du user non-root :
  ```bash
  kubectl exec deploy/movie -- id
  ```
  ```text
  uid=10001(spring) gid=101(spring) groups=101(spring)
  ```
- Vérification du système de fichiers en lecture seule :
  ```bash
  kubectl exec deploy/movie -- touch /test
  ```
  ```text
  touch: cannot touch '/test': Read-only file system
  ```
- Les Pods restent stables et opérationnels en statut **`1/1 Running`**.

---

### ⭐ B2 — Rolling update sans coupure

La stratégie de déploiement a été configurée dans `k8s/20-movie.yaml` :
```yaml
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
```

Sortie du test pendant un `kubectl rollout restart deploy/movie` :
```bash
for i in $(seq 1 120); do curl -s -o /dev/null -w '%{http_code}\n' http://cinema.local/api/movies; sleep 0.2; done | sort | uniq -c
```
```text
120 200
```

> **QB2** — Quel est le résultat ? Quels trois éléments (`strategy`, `readinessProbe`, `shutdown: graceful`) y contribuent, et comment ?

- **Résultat observé :** **100 % de codes HTTP `200 OK`**, sans aucune erreur, coupure ni requête rejetée pendant toute la durée du redémarrage.
- **Rôle des 3 éléments :**
  1. **`strategy: RollingUpdate` (`maxUnavailable: 0`, `maxSurge: 1`) :** Garantit qu'à aucun moment la capacité de service ne diminue en dessous de 2 réplicas. Kubernetes crée d'abord un nouveau Pod supplémentaire avant d'envisager la suppression d'un ancien Pod.
  2. **`readinessProbe` :** Empêche Kubernetes d'ajouter le nouveau Pod aux Endpoints et d'arrêter un ancien Pod tant que la nouvelle instance Spring Boot n'a pas validé `/actuator/health/readiness` (démarrage complet du serveur Tomcat et initialisation de l'API). Le basculement de trafic n'a lieu que lorsque le nouveau Pod est réellement prêt.
  3. **`server.shutdown: graceful` :** Lors de la réception du signal d'extinction (`SIGTERM`), Spring Boot cesse d'accepter de nouvelles requêtes mais attend la fin de l'exécution de toutes les requêtes HTTP déjà en cours de traitement, évitant ainsi d'interrompre brutalement des requêtes clientes (erreurs 502 / Bad Gateway).
