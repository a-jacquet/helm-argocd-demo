# TP Redis — Cache, Sessions et Pub/Sub avec Spring Boot + Kubernetes

> **Prérequis :** Docker Desktop + Kubernetes, kubectl, Helm, ArgoCD (namespace `argocd`), chart `hello-chart` déployé dans le namespace `demo`, projet `demo-app` Spring Boot avec PostgreSQL sur le port 5555.
> Toutes les commandes s'exécutent dans **PowerShell sur Windows**.
> Ce TP s'appuie sur les TPs Helm+ArgoCD et Liquibase précédents.

---

## Ce qu'est Redis

Redis est une base de données **en mémoire** (clé/valeur) ultra-rapide. Contrairement à PostgreSQL qui lit sur disque, Redis lit en RAM — les temps de réponse sont de l'ordre de la **microseconde**. On l'utilise pour trois cas d'usage principaux :

| Cas d'usage       | Pourquoi Redis et pas PostgreSQL                |
| ----------------- | ----------------------------------------------- |
| **Cache**         | Évite de rejouer des requêtes SQL coûteuses     |
| **Sessions HTTP** | Les sessions survivent aux redémarrages de pods |
| **Pub/Sub**       | Communication asynchrone entre composants       |

---

# PHASE 1 — Déployer Redis dans le cluster K8s

## Étape 1 — Installer Redis via Bitnami Helm

```powershell
helm install redis bitnami/redis `
  --namespace demo `
  --set architecture=standalone `
  --set auth.password=redis123 `
  --set master.persistence.enabled=false
```

> **`architecture=standalone`** : un seul nœud Redis, suffisant pour du dev.
> **`persistence.enabled=false`** : pas de volume persistant, simplifie le setup local.

**Vérification :**

```powershell
kubectl get pods -n demo -l app.kubernetes.io/name=redis -w
```

> Attends que `redis-master-0` passe à `1/1 Running`.

---

## Étape 2 — Tester Redis avec redis-cli

```powershell
kubectl exec -it redis-master-0 -n demo -- redis-cli -a redis123 PING
```

> **Résultat attendu :** `PONG`

Teste quelques commandes de base :

```powershell
kubectl exec -it redis-master-0 -n demo -- redis-cli -a redis123
```

Dans le shell redis-cli :

```redis
SET hello "world"
GET hello
DEL hello
KEYS *
EXIT
```

> Ces commandes illustrent le modèle clé/valeur de Redis. `KEYS *` liste toutes les clés — tu verras qu'il n'y en a aucune pour l'instant.

---

## Étape 3 — Récupérer l'URL du service Redis

```powershell
kubectl get svc -n demo | findstr redis
```

> Le service s'appelle `redis-master`. L'URL interne au cluster est :
> `redis://redis-master.demo.svc.cluster.local:6379`

---

## Étape 4 — Lancer un Redis local pour le développement Spring Boot

> **Dépendance :** les phases 2, 3 et 4 utilisent le projet `demo-app` qui tourne en local sur Windows. On lance un Redis Docker séparé pour le dev local.

> **Avertissement Windows :** vérifie d'abord si le port 6379 est libre.

```powershell
netstat -ano | findstr :6379
```

Si le port est libre :

```powershell
docker run -d `
  --name redis-local `
  -p 6379:6379 `
  redis:7-alpine `
  redis-server --requirepass redis123
```

Si le port 6379 est occupé, utilise 6380 :

```powershell
docker run -d `
  --name redis-local `
  -p 6380:6379 `
  redis:7-alpine `
  redis-server --requirepass redis123
```

**Vérification :**

```powershell
docker exec -it redis-local redis-cli -a redis123 PING
```

> **Résultat attendu :** `PONG`

---

---

# PHASE 2 — Cache applicatif avec @Cacheable

> **Objectif :** éviter de rejouer une requête PostgreSQL à chaque appel en stockant le résultat dans Redis.

```powershell
cd C:\wamp64\www\docker-test\demo-app
```

---

## Étape 1 — Ajouter les dépendances Maven

Ajoute ces dépendances dans [demo-app/pom.xml](demo-app/pom.xml), avant la balise `</dependencies>` :

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

---

## Étape 2 — Configurer Redis dans application.properties

Ajoute à la fin de [demo-app/src/main/resources/application.properties](demo-app/src/main/resources/application.properties) :

```powershell
@"
# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=redis123
spring.cache.type=redis
spring.cache.redis.time-to-live=600000
"@ | Add-Content -Path "src\main\resources\application.properties" -Encoding UTF8
```

> **`time-to-live=600000`** : TTL de 10 minutes en millisecondes. Après ce délai, la clé est automatiquement supprimée de Redis.

> **Si tu utilises le port 6380**, remplace `port=6379` par `port=6380`.

---

## Étape 3 — Créer la classe RedisConfig

```powershell
@'
package com.demo.demo_app;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        ObjectMapper mapper = new ObjectMapper()
            .activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
            );

        var jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .build();
    }
}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\RedisConfig.java" -Encoding UTF8
```

> **Pourquoi JSON et pas le sérialiseur binaire par défaut ?** Le binaire est illisible dans redis-cli. Le JSON permet d'inspecter les valeurs directement avec `GET <clé>`.

---

## Étape 4 — Créer l'entité User et le Repository

```powershell
@'
package com.demo.demo_app;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String email;
    private String role;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public User() {}

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setUsername(String username) { this.username = username; }
    public void setEmail(String email) { this.email = email; }
    public void setRole(String role) { this.role = role; }
}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\User.java" -Encoding UTF8
```

```powershell
@'
package com.demo.demo_app;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\UserRepository.java" -Encoding UTF8
```

---

## Étape 5 — Créer le UserService avec @Cacheable

```powershell
@'
package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository repo;

    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    @Cacheable(value = "users", key = "#id")
    public User findById(Long id) {
        log.info(">>> CACHE MISS — requête PostgreSQL pour userId={}", id);
        return repo.findById(id).orElseThrow();
    }

    @CachePut(value = "users", key = "#result.id")
    public User create(User user) {
        log.info(">>> Création utilisateur + mise en cache");
        return repo.save(user);
    }

    @CacheEvict(value = "users", key = "#id")
    public User update(Long id, User updated) {
        log.info(">>> Mise à jour userId={} — cache invalidé", id);
        User user = repo.findById(id).orElseThrow();
        user.setUsername(updated.getUsername());
        user.setEmail(updated.getEmail());
        return repo.save(user);
    }
}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\UserService.java" -Encoding UTF8
```

> **`@Cacheable`** : si la clé existe dans Redis, retourne la valeur sans toucher PostgreSQL.
> **`@CacheEvict`** : supprime la clé du cache à la mise à jour (évite de servir des données périmées).
> **`@CachePut`** : met en cache le résultat sans court-circuiter l'exécution.

---

## Étape 6 — Créer le UserController

```powershell
@'
package com.demo.demo_app;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<User> create(@RequestBody User user) {
        return ResponseEntity.ok(service.create(user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> update(@PathVariable Long id, @RequestBody User user) {
        return ResponseEntity.ok(service.update(id, user));
    }
}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\UserController.java" -Encoding UTF8
```

---

## Étape 7 — Tester le cache

Lance l'app :

```powershell
.\mvnw spring-boot:run
```

**Créer un utilisateur :**

```powershell
Invoke-RestMethod -Uri "http://localhost:8090/users" -Method POST `
  -ContentType "application/json" `
  -Body '{"username":"alice","email":"alice@demo.com","role":"USER"}'
```

**Appeler findById deux fois :**

```powershell
Invoke-RestMethod -Uri "http://localhost:8090/users/1"
Invoke-RestMethod -Uri "http://localhost:8090/users/1"
```

> Dans les logs Spring Boot, le message `>>> CACHE MISS` n'apparaît qu'**une seule fois** — le second appel est servi directement par Redis.

**Inspecter Redis :**

```powershell
docker exec -it redis-local redis-cli -a redis123 KEYS "*"
docker exec -it redis-local redis-cli -a redis123 GET "users::1"
docker exec -it redis-local redis-cli -a redis123 TTL "users::1"
```

> `KEYS *` montre la clé `users::1`. `GET` affiche le JSON de l'utilisateur. `TTL` affiche le temps restant avant expiration (en secondes).

**Mettre à jour et vérifier l'invalidation :**

```powershell
Invoke-RestMethod -Uri "http://localhost:8090/users/1" -Method PUT `
  -ContentType "application/json" `
  -Body '{"username":"alice-updated","email":"alice@demo.com"}'

docker exec -it redis-local redis-cli -a redis123 KEYS "*"
```

> Après le PUT, `KEYS *` renvoie une liste vide — le cache a été invalidé par `@CacheEvict`.

---

---

# PHASE 3 — Session store avec Spring Session

> **Objectif :** les sessions HTTP survivent aux redémarrages de pods et sont partagées entre réplicas.
> **Dépendance :** app en cours d'exécution depuis la Phase 2, Redis local actif.

---

## Étape 1 — Ajouter la dépendance Spring Session

Dans [demo-app/pom.xml](demo-app/pom.xml), ajoute :

```xmll
<dependency>
    <groupId>org.springframework.session</groupId>
    <artifactId>spring-session-data-redis</artifactId>
</dependency>
```

---

## Étape 2 — Configurer Spring Session

Ajoute dans [demo-app/src/main/resources/application.properties](demo-app/src/main/resources/application.properties) :

```powershell
@"
# Spring Session
spring.session.store-type=redis
spring.session.timeout=1800s
spring.session.redis.namespace=demo:sessions
"@ | Add-Content -Path "src\main\resources\application.properties" -Encoding UTF8
```

---

## Étape 3 — Créer les endpoints /login et /me

```powershell
@'
package com.demo.demo_app;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class SessionController {

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestParam String username,
            HttpSession session) {
        session.setAttribute("username", username);
        return ResponseEntity.ok(Map.of(
            "sessionId", session.getId(),
            "message", "Connecté en tant que " + username
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Non connecté"));
        }
        return ResponseEntity.ok(Map.of(
            "username", username,
            "sessionId", session.getId()
        ));
    }
}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\SessionController.java" -Encoding UTF8
```

---

## Étape 4 — Tester la persistance de session

Relance l'app :

```powershell
.\mvnw spring-boot:run
```

**Se connecter et récupérer le sessionId :**

```powershell
$response = Invoke-RestMethod -Uri "http://localhost:8090/login?username=alice" -Method POST -SessionVariable session
$response
```

**Vérifier la session dans Redis :**

```powershell
docker exec -it redis-local redis-cli -a redis123 KEYS "demo:sessions*"
```

> Redis stocke la session sous une clé `demo:sessions:sessions:<sessionId>`.

**Arrêter et relancer l'app (simule un redémarrage de pod) :**

Ctrl+C pour arrêter, puis :

```powershell
.\mvnw spring-boot:run
```

**Rappeler /me avec le même cookie de session :**

```powershell
Invoke-RestMethod -Uri "http://localhost:8090/me" -WebSession $session
```

> **Résultat attendu :** ton nom d'utilisateur est toujours là — la session a survécu au redémarrage car elle était dans Redis, pas dans la mémoire du process Java.

---

---

# PHASE 4 — Pub/Sub Redis (bonus)

> **Objectif :** publier un événement quand un utilisateur est créé et l'écouter depuis un autre composant.
> **Dépendance :** dépendance `spring-boot-starter-data-redis` déjà présente.

---

## Étape 1 — Créer le publisher

```powershell
@'
package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);
    private static final String CHANNEL = "channel:users";
    private final StringRedisTemplate redis;

    public UserEventPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publishCreated(User user) {
        String message = "USER_CREATED:" + user.getId() + ":" + user.getUsername();
        redis.convertAndSend(CHANNEL, message);
        log.info(">>> Event publié sur {} : {}", CHANNEL, message);
    }
}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\UserEventPublisher.java" -Encoding UTF8
```

---

## Étape 2 — Créer le listener

```powershell
@'
package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class UserEventListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(UserEventListener.class);

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info(">>> Event reçu sur canal Redis : {}", message.toString());
    }
}
'@ | Set-Content -Path "src\main\java\com\demo\demo_app\UserEventListener.java" -Encoding UTF8
```

---

## Étape 3 — Configurer le container d'écoute

Ajoute dans `RedisConfig.java`, dans la classe (avant la dernière accolade) :

```java
@Bean
org.springframework.data.redis.listener.RedisMessageListenerContainer listenerContainer(
        RedisConnectionFactory factory,
        UserEventListener listener) {
    var container = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
    container.setConnectionFactory(factory);
    container.addMessageListener(listener,
        new org.springframework.data.redis.listener.PatternTopic("channel:users"));
    return container;
}
```

---

## Étape 4 — Brancher le publisher dans UserService

Modifie la méthode `create` dans `UserService.java` pour appeler le publisher :

```java
private final UserEventPublisher publisher;

public UserService(UserRepository repo, UserEventPublisher publisher) {
    this.repo = repo;
    this.publisher = publisher;
}

@CachePut(value = "users", key = "#result.id")
public User create(User user) {
    User saved = repo.save(user);
    publisher.publishCreated(saved);
    return saved;
}
```

---

## Étape 5 — Tester le Pub/Sub

Relance l'app et crée un utilisateur :

```powershell
.\mvnw spring-boot:run
```

```powershell
Invoke-RestMethod -Uri "http://localhost:8090/users" -Method POST `
  -ContentType "application/json" `
  -Body '{"username":"bob","email":"bob@demo.com","role":"USER"}'
```

> Dans les logs, tu dois voir en quasi simultané :
>
> ```
> >>> Event publié sur channel:users : USER_CREATED:2:bob
> >>> Event reçu sur canal Redis : USER_CREATED:2:bob
> ```

> **Pourquoi publisher et listener voient tous les deux le message ?** Ils tournent dans le même process. En production, le listener serait dans un microservice séparé et recevrait l'événement de la même façon.

---

---

# PHASE 5 — Intégration GitOps avec ArgoCD

## Étape 1 — Mettre à jour le Chart Helm

Le chart doit injecter l'URL et le mot de passe Redis dans les pods. Ajoute dans [hello-chart/values.yaml](hello-chart/values.yaml) :

```powershell
@"

redis:
  host: redis-master.demo.svc.cluster.local
  port: 6379
  password: redis123
"@ | Add-Content -Path "hello-chart\values.yaml" -Encoding UTF8
```

Crée un Secret Redis dans le chart :

```powershell
@'
apiVersion: v1
kind: Secret
metadata:
  name: {{ .Release.Name }}-redis-secret
  namespace: {{ .Release.Namespace }}
type: Opaque
stringData:
  password: {{ .Values.redis.password }}
'@ | Set-Content -Path "hello-chart\templates\secret-redis.yaml" -Encoding UTF8
```

Ajoute les variables d'environnement dans le Deployment. Dans [hello-chart/templates/deployment.yaml](hello-chart/templates/deployment.yaml), ajoute dans la section `containers` :

```yaml
          env:
            - name: SPRING_DATA_REDIS_HOST
              value: "{{ .Values.redis.host }}"
            - name: SPRING_DATA_REDIS_PORT
              value: "{{ .Values.redis.port }}"
            - name: SPRING_DATA_REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Release.Name }}-redis-secret
                  key: password
```

---

## Étape 2 — Pousser sur GitHub et observer ArgoCD

```powershell
cd C:\wamp64\www\docker-test
git add hello-chart/
git commit -m "feat: add redis deployment and env injection"
git push
```

**Forcer la synchronisation ArgoCD :**

```powershell
kubectl patch application hello-app -n argocd --type merge `
  -p '{\"operation\":{\"initiatedBy\":{\"username\":\"admin\"},\"sync\":{\"revision\":\"HEAD\"}}}'
```

**Vérification dans l'UI ArgoCD (https://localhost:8443) :**

```powershell
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

> Tu dois voir dans le graphe de l'app `hello-app` : le Secret `hello-app-redis-secret` et le pod `redis-master-0` tous en vert **Healthy**.

---

## Récapitulatif des commandes Redis utiles

```powershell
# Connexion au Redis K8s
kubectl exec -it redis-master-0 -n demo -- redis-cli -a redis123

# Connexion au Redis local
docker exec -it redis-local redis-cli -a redis123
```

```redis
KEYS *              -- lister toutes les clés
GET <clé>          -- lire une valeur
TTL <clé>          -- temps de vie restant (secondes)
DEL <clé>          -- supprimer une clé
FLUSHDB            -- vider toute la base (dev uniquement)
MONITOR            -- voir les commandes en temps réel
SUBSCRIBE channel:users   -- écouter un canal Pub/Sub
```

---

## Problèmes courants

| Problème                                   | Cause                                     | Solution                                                                                |
| ------------------------------------------ | ----------------------------------------- | --------------------------------------------------------------------------------------- |
| `Connection refused` sur 6379              | Port occupé par un autre service          | Utilise le port 6380 et adapte `application.properties`                                 |
| `WRONGPASS invalid username-password pair` | Mot de passe incorrect                    | Vérifie `spring.data.redis.password` dans `application.properties`                      |
| Cache non invalidé après PUT               | `@CacheEvict` mal configuré               | Vérifie que `key="#id"` correspond bien à la clé utilisée dans `@Cacheable`             |
| Session perdue après redémarrage           | `spring.session.store-type` non configuré | Vérifie que `spring-session-data-redis` est dans le `pom.xml`                           |
| Listener ne reçoit pas les messages        | Container d'écoute non démarré            | Vérifie que le bean `RedisMessageListenerContainer` est bien déclaré dans `RedisConfig` |
