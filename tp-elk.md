# TP ELK — Centralisation des logs Spring Boot avec Kubernetes

> **Prérequis :** Docker Desktop + Kubernetes (contexte `docker-desktop`), kubectl, Helm, ArgoCD, Redis, PostgreSQL sur port 5555, projet `demo-app` Spring Boot dans `C:\wamp64\www\docker-test\demo-app`.
> Toutes les commandes s'exécutent dans **PowerShell sur Windows**.
> Ce TP s'appuie sur les TPs Helm+ArgoCD, Liquibase et Redis précédents.

---

## Ce qu'est la stack ELK

| Composant | Rôle |
|---|---|
| **Elasticsearch** | Base de données de logs (stockage + recherche full-text) |
| **Logstash** | Pipeline : reçoit, transforme et envoie les logs vers ES |
| **Kibana** | Interface web pour explorer et visualiser les logs |

Le flux de données :

```
Spring Boot app
    └── LogstashTcpSocketAppender (logback)
        └── Logstash :5044  (TCP JSON)
            └── Elasticsearch (index spring-logs-YYYY.MM.dd)
                └── Kibana (visualisation)
```

> **Note architecture :** dans ce TP, ELK tourne en **Docker local** (comme PostgreSQL et Redis) pour éviter les problèmes de pull d'images dans le cluster Kubernetes. En production, ELK serait déployé dans K8s via Helm.

---

> **Avertissement ressources :** ELK est gourmand. Avant de commencer, vérifie que Docker Desktop a au moins **6 Go de RAM** alloués.
> Docker Desktop → Settings → Resources → Memory → 6144 MB minimum.

---

# PHASE 1 — Déployer la stack ELK en Docker local

## Étape 1 — Créer le réseau Docker partagé

```powershell
docker network create elk-local
```

Crée un réseau Docker dédié pour que les 3 composants ELK puissent communiquer entre eux par nom de container.

---

## Étape 2 — Déployer Elasticsearch

```powershell
docker run -d --name elasticsearch-local `
  --network elk-local `
  -e "discovery.type=single-node" `
  -e "xpack.security.enabled=false" `
  -e "ES_JAVA_OPTS=-Xmx512m -Xms512m" `
  -p 9200:9200 `
  docker.elastic.co/elasticsearch/elasticsearch:8.5.1
```

> **`discovery.type=single-node`** : mode nœud unique, pas de cluster multi-nœuds.
> **`xpack.security.enabled=false`** : désactive l'auth pour simplifier le dev local.

**Vérification (~30 secondes) :**

```powershell
Invoke-RestMethod -Uri "http://localhost:9200/_cluster/health"
```

> **Résultat attendu :** `status` = `"green"` ou `"yellow"`.

---

## Étape 3 — Déployer Kibana

```powershell
docker run -d --name kibana-local `
  --network elk-local `
  -e "ELASTICSEARCH_HOSTS=http://elasticsearch-local:9200" `
  -p 5601:5601 `
  docker.elastic.co/kibana/kibana:8.5.1
```

> Kibana se connecte à Elasticsearch via le nom de container `elasticsearch-local` sur le réseau `elk-local`.

**Vérification (~1 min) :**

Ouvre **http://localhost:5601** dans le navigateur.

> Kibana peut mettre 1-2 minutes à démarrer complètement. Si tu vois "Kibana server is not ready yet", attends et rafraîchis.

---

## Étape 4 — Créer le fichier pipeline Logstash

```powershell
New-Item -ItemType Directory -Force -Path "C:\wamp64\www\docker-test\logstash-pipeline"
```

Crée le fichier [logstash-pipeline/logstash.conf](logstash-pipeline/logstash.conf) dans VSCode avec ce contenu :

```
input {
  tcp {
    port => 5044
    codec => json_lines
  }
}

filter {
  if [level] {
    mutate {
      uppercase => [ "level" ]
    }
  }
  date {
    match => [ "timestamp", "ISO8601" ]
    target => "@timestamp"
  }
}

output {
  elasticsearch {
    hosts => ["http://elasticsearch-local:9200"]
    index => "spring-logs-%{+YYYY.MM.dd}"
  }
  stdout {
    codec => rubydebug
  }
}
```

> Le pipeline reçoit du JSON sur TCP 5044, met le niveau en majuscules, normalise le timestamp, et envoie vers Elasticsearch dans un index journalier.

---

## Étape 5 — Déployer Logstash

```powershell
docker run -d --name logstash-local `
  --network elk-local `
  -p 5044:5044 `
  -v "C:\wamp64\www\docker-test\logstash-pipeline:/usr/share/logstash/pipeline" `
  docker.elastic.co/logstash/logstash:8.5.1
```

**Vérification (~1 min) :**

```powershell
docker logs logstash-local --follow
```

> Attends `Pipelines running` dans les logs, puis Ctrl+C.

**Test du pipeline :**

```powershell
$testLog = '{"level":"INFO","message":"test logstash","service":"test"}' + "`n"
$tcp = New-Object System.Net.Sockets.TcpClient("localhost", 5044)
$stream = $tcp.GetStream()
$bytes = [System.Text.Encoding]::UTF8.GetBytes($testLog)
$stream.Write($bytes, 0, $bytes.Length)
$tcp.Close()

Invoke-RestMethod -Uri "http://localhost:9200/spring-logs-*/_count"
```

> **Résultat attendu :** `count` = 1.

---
---

# PHASE 2 — Configurer Spring Boot pour envoyer ses logs à Logstash

> **Dépendance :** les 3 containers Docker ELK doivent tourner (`docker ps` doit lister `elasticsearch-local`, `kibana-local`, `logstash-local`).

```powershell
cd C:\wamp64\www\docker-test\demo-app
```

---

## Étape 1 — Ajouter la dépendance logstash-logback-encoder

Dans [demo-app/pom.xml](demo-app/pom.xml), ajoute avant `</dependencies>` :

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

---

## Étape 2 — Créer logback-spring.xml

Crée [demo-app/src/main/resources/logback-spring.xml](demo-app/src/main/resources/logback-spring.xml) dans VSCode :

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <!-- LOGSTASH_HOST = variable d'env injectée par K8s, fallback localhost pour dev -->
        <destination>${LOGSTASH_HOST:-localhost}:${LOGSTASH_PORT:-5044}</destination>
        <keepAliveDuration>5 minutes</keepAliveDuration>
        <reconnectionDelay>10 second</reconnectionDelay>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"demo-app","env":"local"}</customFields>
            <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
                <maxDepthPerThrowable>30</maxDepthPerThrowable>
                <maxLength>2048</maxLength>
                <rootCauseFirst>true</rootCauseFirst>
            </throwableConverter>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="LOGSTASH"/>
    </root>

    <logger name="com.demo.demo_app" level="DEBUG"/>
    <logger name="org.hibernate.SQL" level="DEBUG"/>

</configuration>
```

---

## Étape 3 — Ajouter le nom de l'app dans application.properties

Ajoute dans [demo-app/src/main/resources/application.properties](demo-app/src/main/resources/application.properties) :

```properties
spring.application.name=demo-app
spring.profiles.active=local
```

---

## Étape 4 — Créer le LogTestController

Crée [demo-app/src/main/java/com/demo/demo_app/LogTestController.java](demo-app/src/main/java/com/demo/demo_app/LogTestController.java) dans VSCode :

```java
package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogTestController {

    private static final Logger log = LoggerFactory.getLogger(LogTestController.class);

    @GetMapping("/log/info")
    public String logInfo() {
        log.info("Ceci est un log INFO de test");
        return "INFO log envoye";
    }

    @GetMapping("/log/warn")
    public String logWarn() {
        log.warn("Ceci est un log WARN - quelque chose d inhabituel");
        return "WARN log envoye";
    }

    @GetMapping("/log/error")
    public String logError() {
        try {
            String s = null;
            s.length();
        } catch (NullPointerException e) {
            log.error("NullPointerException simulee pour le test ELK", e);
        }
        return "ERROR log envoye avec stack trace";
    }
}
```

> **Important :** créer ce fichier directement dans VSCode (pas via `Set-Content` PowerShell) pour éviter le BOM UTF-8 qui casse la compilation Java.

---

## Étape 5 — Lancer l'app et vérifier les logs

Assure-toi que Logstash tourne :

```powershell
docker ps --filter name=logstash-local
```

Relance l'app :

```powershell
.\mvnw clean spring-boot:run
```

Génère les 3 types de logs :

```powershell
Invoke-RestMethod -Uri "http://localhost:8090/log/info"
Invoke-RestMethod -Uri "http://localhost:8090/log/warn"
Invoke-RestMethod -Uri "http://localhost:8090/log/error"
```

Vérifie que les logs arrivent dans Elasticsearch :

```powershell
Invoke-RestMethod -Uri "http://localhost:9200/spring-logs-*/_count"
```

> **Résultat attendu :** `count` supérieur à 0.

---
---

# PHASE 3 — Explorer les logs dans Kibana

> Kibana est accessible directement sur **http://localhost:5601** — aucun port-forward nécessaire.

---

## Étape 1 — Créer le Data View dans Kibana

1. Ouvre **http://localhost:5601**
2. Menu gauche → **Stack Management** → **Data Views**
3. Clique **Create data view**
4. Name : `spring-logs`
5. Index pattern : `spring-logs-*`
6. Timestamp field : `@timestamp`
7. Clique **Save data view to Kibana**

---

## Étape 2 — Explorer les logs dans Discover

1. Menu gauche → **Discover**
2. Sélectionne le data view `spring-logs`
3. Tu vois tous les logs des 15 dernières minutes

**Filtrer par niveau (KQL) :**

```
level : "ERROR"
```

```
level : "WARN" or level : "ERROR"
```

**Filtrer par service :**

```
service : "demo-app"
```

**Rechercher dans les messages :**

```
message : "NullPointerException"
```

**Voir la stack trace complète :**

Clique sur un log ERROR → expand → champ `stack_trace`.

---

## Étape 3 — Créer un dashboard basique

1. Menu gauche → **Dashboard** → **Create dashboard**
2. Clique **Create visualization**

**Visualisation 1 — Compteur par niveau :**
- Type : `Bar vertical`
- Data view : `spring-logs`
- Horizontal axis : `level` (Terms)
- Vertical axis : `Count`
- Sauvegarde : "Logs par niveau"

**Visualisation 2 — Volume temporel :**
- Type : `Line`
- Horizontal axis : `@timestamp` (Date histogram, interval Auto)
- Vertical axis : `Count`
- Sauvegarde : "Volume de logs"

**Visualisation 3 — Compteur total d'erreurs :**
- Type : `Metric`
- Filter : `level : "ERROR"`
- Metric : `Count`
- Sauvegarde : "Total ERRORs"

Ajoute les 3 visualisations au dashboard → **Save** → "Dashboard demo-app".

---

## Étape 4 — Scénario de debug production

Simule une erreur et retrouve-la en moins de 30 secondes :

```powershell
Invoke-RestMethod -Uri "http://localhost:8090/log/error"
```

Dans Kibana → **Discover** :

1. Filtre : `level : "ERROR"`
2. Clique sur le log le plus récent
3. Vérifie : `message`, `stack_trace`, `@timestamp`, `service`

---
---

# PHASE 4 — Filebeat comme collecteur de logs K8s

> **Objectif :** collecter automatiquement les logs de tous les pods du namespace `demo` sans modifier le code Spring Boot.
> **Note :** Filebeat tourne dans K8s (DaemonSet) mais envoie vers Logstash en Docker local.

---

## Étape 1 — Déployer Filebeat comme DaemonSet

```powershell
kubectl create namespace logging
helm install filebeat elastic/filebeat `
  --namespace logging `
  --set tolerations[0].operator=Exists `
  --set resources.requests.memory=64Mi `
  --set resources.limits.memory=128Mi
```

**Vérification :**

```powershell
kubectl get pods -n logging -l app.kubernetes.io/name=filebeat
```

---

## Étape 2 — Configurer Filebeat

Crée le fichier `filebeat-configmap.yaml` dans VSCode :

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: filebeat-config
  namespace: logging
data:
  filebeat.yml: |
    filebeat.autodiscover:
      providers:
        - type: kubernetes
          node: ${NODE_NAME}
          hints.enabled: true
          templates:
            - condition:
                equals:
                  kubernetes.namespace: demo
              config:
                - type: container
                  paths:
                    - /var/log/containers/*${data.kubernetes.container.id}.log
                  processors:
                    - add_kubernetes_metadata:
                        host: ${NODE_NAME}

    output.logstash:
      hosts: ["host.docker.internal:5044"]
```

> **`host.docker.internal`** : depuis un pod K8s sur Docker Desktop Windows, c'est l'adresse du host Windows — permet d'atteindre `logstash-local` qui tourne en Docker.

```powershell
kubectl apply -f filebeat-configmap.yaml
```

---

## Étape 3 — Comparaison Logback direct vs Filebeat

| | Logback → Logstash direct | Filebeat → Logstash |
|---|---|---|
| **Champs disponibles** | `level`, `message`, `service`, `stack_trace` structurés | `message` brut + métadonnées K8s |
| **Format** | JSON structuré | Ligne de log telle quelle |
| **Config nécessaire** | Dans le code Java | Aucune modification du code |
| **Meilleur pour** | Logs applicatifs riches | Collecte exhaustive de tous les pods |

---
---

# PHASE 5 — Intégration GitOps avec ArgoCD

## Étape 1 — Mettre à jour le Chart Helm

Dans [hello-chart/values.yaml](hello-chart/values.yaml), ajoute :

```yaml
logstash:
  host: host.docker.internal
  port: 5044
```

Dans [hello-chart/templates/deployment.yaml](hello-chart/templates/deployment.yaml), ajoute dans `env` :

```yaml
            - name: LOGSTASH_HOST
              value: "{{ .Values.logstash.host }}"
            - name: LOGSTASH_PORT
              value: {{ .Values.logstash.port | quote }}
```

> **`host.docker.internal`** : depuis un pod K8s, cette adresse pointe vers le host Windows où tourne `logstash-local` en Docker.

---

## Étape 2 — Pousser et observer ArgoCD

```powershell
cd C:\wamp64\www\docker-test
git add hello-chart/ logstash-pipeline/ filebeat-configmap.yaml
git commit -m "feat: add ELK stack integration"
git push
```

```powershell
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

---

## Récapitulatif des commandes de vérification

```powershell
# État des containers ELK
docker ps --filter name=elasticsearch-local
docker ps --filter name=kibana-local
docker ps --filter name=logstash-local

# Nombre de logs dans ES
Invoke-RestMethod "http://localhost:9200/spring-logs-*/_count"

# Indices créés dans ES
Invoke-RestMethod "http://localhost:9200/_cat/indices?v"

# Logs Logstash en temps réel
docker logs logstash-local -f

# Logs Elasticsearch
docker logs elasticsearch-local -f
```

---

## Problèmes courants

| Problème | Cause | Solution |
|---|---|---|
| ES ne répond pas sur 9200 | Container pas encore démarré | Attends 30s et retente |
| Kibana "Not ready" | ES pas encore prêt | Attends 1-2 min après le démarrage d'ES |
| `spring-logs-*` absent dans Kibana | Aucun log reçu | Vérifie que `logstash-local` tourne et relance `.\mvnw spring-boot:run` |
| Logstash ne reçoit rien | Port 5044 occupé par Windows | `netstat -ano \| findstr :5044` — change le port si nécessaire |
| Filebeat n'envoie rien vers Logstash | `host.docker.internal` non résolu | Vérifie la connectivité avec `kubectl exec` depuis un pod |
