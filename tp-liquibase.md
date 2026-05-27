# TP Liquibase — Migrations de base de données avec Helm + ArgoCD

> **Prérequis :** Java 17+ installé, Docker Desktop + Kubernetes, kubectl, Helm, ArgoCD (namespace `argocd`), chart `hello-chart` dans `C:\wamp64\www\docker-test`.
> Toutes les commandes s'exécutent dans **PowerShell sur Windows**.
> Ce TP s'appuie sur le chart déployé dans le TP précédent (`hello-chart`).

---

## Ce qu'est Liquibase

Liquibase est un outil de **versioning de schéma de base de données**. Comme Git versionne ton code, Liquibase versionne tes changements SQL via des fichiers appelés **changesets**. À chaque démarrage de l'app, Liquibase compare les changesets déjà appliqués (tracés dans la table `DATABASECHANGELOG`) avec ceux présents dans les fichiers, et n'applique **que les nouveaux**.

---

# PHASE 1 — Liquibase en local (Spring Boot + PostgreSQL)

## Étape 1 — Lancer PostgreSQL via Docker

```powershell
docker run -d `
  --name postgres-local `
  -e POSTGRES_DB=demodb `
  -e POSTGRES_USER=demo `
  -e POSTGRES_PASSWORD=demo123 `
  -p 5432:5432 `
  postgres:15-alpine
```

Lance un container PostgreSQL accessible sur `localhost:5432`.

**Vérification :**

```powershell
docker ps --filter name=postgres-local
```

> **Résultat attendu :** le container apparaît avec le statut `Up`.

---

## Étape 2 — Créer le projet Spring Boot

```powershell
cd C:\wamp64\www\docker-test

curl.exe -L -o demo-app.zip "https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=3.3.0&baseDir=demo-app&groupId=com.demo&artifactId=demo-app&dependencies=web,data-jpa,liquibase,postgresql&javaVersion=17"

Expand-Archive -Path demo-app.zip -DestinationPath . -Force
cd demo-app
```

Génère un projet Spring Boot minimal avec les dépendances JPA, Liquibase et le driver PostgreSQL, puis le décompresse.

**Vérification :**

```powershell
ls
```

> Tu dois voir `pom.xml`, `mvnw`, `mvnw.cmd` et le dossier `src/`.

---

## Étape 3 — Configurer la connexion à PostgreSQL

Remplace le contenu de `src\main\resources\application.properties` :

```powershell
@'
spring.datasource.url=jdbc:postgresql://localhost:5432/demodb
spring.datasource.username=demo
spring.datasource.password=demo123
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=none
spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml
spring.jpa.show-sql=true
'@ | Set-Content -Path "src\main\resources\application.properties" -Encoding UTF8
```

> **`ddl-auto=none`** : désactive la gestion du schéma par Hibernate — c'est Liquibase qui en est responsable.

---

## Étape 4 — Créer le fichier changelog principal

Le **changelog master** est le point d'entrée de Liquibase. Il inclut les autres fichiers de changesets dans l'ordre d'exécution.

```powershell
New-Item -ItemType Directory -Path "src\main\resources\db\changelog\changesets" -Force

@'
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <include file="db/changelog/changesets/001-create-users-table.xml"/>

</databaseChangeLog>
'@ | Set-Content -Path "src\main\resources\db\changelog\db.changelog-master.xml" -Encoding UTF8
```

> **Structure :** le master ne contient pas de SQL — il orchestre les inclusions. Chaque changeset est dans son propre fichier numéroté.

---

## Étape 5 — Créer le premier changeset : table "users"

```powershell
@'
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="001" author="a-jacquet">
        <createTable tableName="users">
            <column name="id" type="BIGSERIAL">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="username" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="email" type="VARCHAR(255)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

</databaseChangeLog>
'@ | Set-Content -Path "src\main\resources\db\changelog\changesets\001-create-users-table.xml" -Encoding UTF8
```

---

## Étape 6 — Lancer l'app et observer Liquibase

```powershell
.\mvnw spring-boot:run
```

> **Avertissement Windows :** si tu vois `'.\mvnw' is not recognized`, utilise `.\mvnw.cmd spring-boot:run`.

Dans les logs, tu dois voir Liquibase s'exécuter avant le démarrage de Spring :

```
INFO  liquibase : Successfully acquired change log lock
INFO  liquibase : Running Changeset: 001-create-users-table.xml::001::a-jacquet
INFO  liquibase : Table users created
INFO  liquibase : ChangeSet 001-create-users-table.xml::001::a-jacquet ran successfully
```

**Vérification en base :**

Ouvre un **second terminal** et interroge PostgreSQL :

```powershell
# Voir la table de tracking Liquibase
docker exec -it postgres-local psql -U demo -d demodb -c "SELECT id, author, filename, dateexecuted FROM databasechangelog;"

# Vérifier que la table users existe
docker exec -it postgres-local psql -U demo -d demodb -c "\dt"
```

> `DATABASECHANGELOG` est la table interne de Liquibase qui trace chaque changeset appliqué avec son auteur et sa date.

Ctrl+C pour arrêter l'app.

---

## Étape 7 — Deuxième changeset : colonne "role"

Ajoute l'include dans le master changelog :

```powershell
$master = Get-Content "src\main\resources\db\changelog\db.changelog-master.xml" -Raw
$master = $master -replace '</databaseChangeLog>', '    <include file="db/changelog/changesets/002-add-role-column.xml"/>
</databaseChangeLog>'
$master | Set-Content "src\main\resources\db\changelog\db.changelog-master.xml" -Encoding UTF8
```

Crée le changeset :

```powershell
@'
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="002" author="a-jacquet">
        <addColumn tableName="users">
            <column name="role" type="VARCHAR(50)" defaultValue="USER">
                <constraints nullable="false"/>
            </column>
        </addColumn>
    </changeSet>

</databaseChangeLog>
'@ | Set-Content -Path "src\main\resources\db\changelog\changesets\002-add-role-column.xml" -Encoding UTF8
```

Relance l'app :

```powershell
.\mvnw spring-boot:run
```

> Dans les logs, Liquibase ne rejouera PAS le changeset 001 (déjà tracé dans `DATABASECHANGELOG`). Il applique **uniquement** le 002. C'est l'**idempotence** de Liquibase.

**Vérification :**

```powershell
docker exec -it postgres-local psql -U demo -d demodb -c "\d users"
```

> La colonne `role` doit apparaître dans la structure de la table.

Ctrl+C pour arrêter l'app.

---

## Étape 8 — Rollback du dernier changeset

On utilise l'image Docker officielle Liquibase pour lancer la CLI sans l'installer sur Windows.

```powershell
docker run --rm `
  -v "${PWD}/src/main/resources:/liquibase/changelog" `
  liquibase/liquibase:4.25 `
  --url="jdbc:postgresql://host.docker.internal:5555/demodb" `
  --username=demo `
  --password=demo123 `
  --changeLogFile=changelog/db/changelog/db.changelog-master.xml `
  rollbackCount 1
```

> **`host.docker.internal`** : depuis un container Docker sur Windows, c'est l'adresse du `localhost` de la machine hôte. C'est la seule façon d'atteindre le `postgres-local` depuis un autre container sans network partagé.

**Vérification :**

```powershell
docker exec -it postgres-local psql -U demo -d demodb -c "\d users"
```

> La colonne `role` a disparu. La table `DATABASECHANGELOG` ne contient plus que le changeset 001.

---
---

# PHASE 2 — Intégration dans le Chart Helm

> **Objectif :** Liquibase s'exécute automatiquement avant le démarrage de l'app à chaque `helm upgrade`, via un **initContainer**.
>
> **Pourquoi un initContainer et pas un Job K8s ?**
> Un initContainer s'exécute dans le même pod que l'app, dans le même cycle de vie. Si les migrations échouent, l'app ne démarre **jamais** — ce qui empêche de servir du trafic avec un schéma incompatible. Un Job séparé nécessiterait une coordination externe et compliquerait la gestion des erreurs.

```powershell
# Revenir dans le dossier de travail principal
cd C:\wamp64\www\docker-test
```

---

## Étape 1 — Déployer PostgreSQL dans Kubernetes

```powershell
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

helm install postgres bitnami/postgresql `
  --namespace demo `
  --set auth.database=demodb `
  --set auth.username=demo `
  --set auth.password=demo123 `
  --set auth.postgresPassword=admin123
```

Déploie PostgreSQL dans le namespace `demo` via le chart officiel Bitnami.

**Vérification :**

```powershell
kubectl get pods -n demo -l app.kubernetes.io/name=postgresql
```

> **Résultat attendu :** le pod `postgres-postgresql-0` en statut `Running`.

Le nom DNS interne du service PostgreSQL dans le cluster sera :
`postgres-postgresql.demo.svc.cluster.local:5432`

---

## Étape 2 — Créer le Secret K8s pour le mot de passe

```powershell
kubectl create secret generic hello-release-db-secret `
  --namespace demo `
  --from-literal=password=demo123
```

Stocke le mot de passe PostgreSQL dans un Secret Kubernetes chiffré, plutôt qu'en clair dans `values.yaml`.

**Vérification :**

```powershell
kubectl get secret hello-release-db-secret -n demo
```

---

## Étape 3 — Créer le ConfigMap avec les changelogs

On stocke les fichiers Liquibase directement dans Kubernetes. Pour la version K8s, on consolide tous les changesets dans un seul fichier master (les ConfigMap ne supportent pas les chemins avec sous-dossiers).

```powershell
@'
apiVersion: v1
kind: ConfigMap
metadata:
  name: hello-release-liquibase-changelog
  namespace: demo
data:
  db.changelog-master.xml: |
    <?xml version="1.0" encoding="UTF-8"?>
    <databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

        <changeSet id="001" author="a-jacquet">
            <createTable tableName="users">
                <column name="id" type="BIGSERIAL">
                    <constraints primaryKey="true" nullable="false"/>
                </column>
                <column name="username" type="VARCHAR(100)">
                    <constraints nullable="false"/>
                </column>
                <column name="email" type="VARCHAR(255)">
                    <constraints nullable="false" unique="true"/>
                </column>
                <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                    <constraints nullable="false"/>
                </column>
            </createTable>
        </changeSet>

        <changeSet id="002" author="a-jacquet">
            <addColumn tableName="users">
                <column name="role" type="VARCHAR(50)" defaultValue="USER">
                    <constraints nullable="false"/>
                </column>
            </addColumn>
        </changeSet>

    </databaseChangeLog>
'@ | Set-Content -Path "liquibase-configmap.yaml" -Encoding UTF8

kubectl apply -f liquibase-configmap.yaml
```

**Vérification :**

```powershell
kubectl get configmap hello-release-liquibase-changelog -n demo
```

---

## Étape 4 — Modifier le Deployment pour ajouter l'initContainer

Remplace le contenu de `hello-chart\templates\deployment.yaml` :

```powershell
@'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-hello
  namespace: {{ .Release.Namespace }}
  labels:
    app: {{ .Release.Name }}-hello
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}-hello
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}-hello
    spec:
      initContainers:
        - name: liquibase-migrations
          image: liquibase/liquibase:4.25
          args:
            - "--url={{ .Values.liquibase.url }}"
            - "--username={{ .Values.liquibase.username }}"
            - "--password=$(DB_PASSWORD)"
            - "--changeLogFile=/liquibase/changelog/db.changelog-master.xml"
            - "update"
          env:
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Release.Name }}-db-secret
                  key: password
          volumeMounts:
            - name: changelog-volume
              mountPath: /liquibase/changelog
      containers:
        - name: hello
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.containerPort }}
      volumes:
        - name: changelog-volume
          configMap:
            name: {{ .Release.Name }}-liquibase-changelog
'@ | Set-Content -Path "hello-chart\templates\deployment.yaml" -Encoding UTF8
```

---

## Étape 5 — Mettre à jour values.yaml

```powershell
@'
replicaCount: 1

image:
  repository: nginx
  tag: alpine
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80

containerPort: 80

liquibase:
  url: jdbc:postgresql://postgres-postgresql.demo.svc.cluster.local:5432/demodb
  username: demo
'@ | Set-Content -Path "hello-chart\values.yaml" -Encoding UTF8
```

> Le mot de passe n'est **pas** dans `values.yaml` — il est dans le Secret K8s créé à l'étape 2.

---

## Étape 6 — Déployer avec helm upgrade

```powershell
helm upgrade hello-release hello-chart --namespace demo
```

**Observer la séquence de démarrage :**

```powershell
kubectl get pods -n demo -w
```

> Tu verras le pod passer par les états :
> ```
> hello-release-hello-xxx   0/1   Init:0/1   0   5s    ← initContainer Liquibase en cours
> hello-release-hello-xxx   0/1   PodInitializing   0   30s  ← migrations terminées
> hello-release-hello-xxx   1/1   Running   0   31s    ← app démarrée
> ```

**Voir les logs de l'initContainer :**

```powershell
# Remplace <pod-name> par le nom réel du pod (kubectl get pods -n demo)
kubectl logs -n demo <pod-name> -c liquibase-migrations
```

> **Résultat attendu :**
> ```
> Successfully acquired change log lock
> Running Changeset: db.changelog-master.xml::001::a-jacquet
> Running Changeset: db.changelog-master.xml::002::a-jacquet
> Update command completed successfully.
> ```

---

## Étape 7 — Simuler un déploiement avec une nouvelle migration

### 7a — Ajouter un changeset dans le ConfigMap

Ouvre `liquibase-configmap.yaml` et ajoute le changeset 003 avant la balise fermante `</databaseChangeLog>` :

```xml
        <changeSet id="003" author="a-jacquet">
            <addColumn tableName="users">
                <column name="last_login" type="TIMESTAMP"/>
            </addColumn>
        </changeSet>
```

Réapplique le ConfigMap :

```powershell
kubectl apply -f liquibase-configmap.yaml
```

### 7b — Déclencher un nouveau déploiement

```powershell
kubectl rollout restart deployment/hello-release-hello -n demo
```

Force Kubernetes à recréer le pod, ce qui rejoue l'initContainer.

**Vérification dans les logs :**

```powershell
kubectl logs -n demo -l app=hello-release-hello -c liquibase-migrations --previous
```

> Liquibase doit afficher uniquement `Running Changeset: ...::003::a-jacquet` — les changesets 001 et 002 sont ignorés car déjà tracés.

---

## Étape 8 — Simuler un échec de migration

Ajoute un changeset invalide dans `liquibase-configmap.yaml` (colonne déjà existante) :

```xml
        <changeSet id="004" author="a-jacquet">
            <addColumn tableName="users">
                <column name="role" type="VARCHAR(50)"/>
            </addColumn>
        </changeSet>
```

```powershell
kubectl apply -f liquibase-configmap.yaml
kubectl rollout restart deployment/hello-release-hello -n demo
kubectl get pods -n demo -w
```

> **Résultat attendu :** le pod reste bloqué à `Init:Error` ou `Init:CrashLoopBackOff` — l'app nginx ne démarre **jamais**. C'est le comportement voulu : une migration cassée bloque tout.

**Corriger en supprimant le changeset 004 du ConfigMap, puis :**

```powershell
kubectl apply -f liquibase-configmap.yaml
kubectl rollout restart deployment/hello-release-hello -n demo
```

---
---

# PHASE 3 — Pipeline GitOps avec ArgoCD

## Étape 1 — Pousser les modifications sur GitHub

```powershell
cd C:\wamp64\www\docker-test

git add hello-chart/
git add liquibase-configmap.yaml
git commit -m "feat: add liquibase initContainer to helm chart"
git push
```

---

## Étape 2 — Observer ArgoCD détecter le commit

Dans un terminal, expose l'UI ArgoCD :

```powershell
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

Ouvre **https://localhost:8443**.

> ArgoCD vérifie le repo toutes les 3 minutes. Pour forcer une sync immédiate :

```powershell
kubectl patch application hello-app -n argocd --type merge -p '{\"operation\":{\"initiatedBy\":{\"username\":\"admin\"},\"sync\":{\"revision\":\"HEAD\"}}}'
```

---

## Étape 3 — Suivre le déploiement dans l'UI ArgoCD

Dans l'UI, clique sur l'app `hello-app`. Tu verras le graphe :

```
Application
└── Deployment
    └── Pod
        ├── initContainer: liquibase-migrations  (vert quand migrations OK)
        └── Container: hello  (vert quand app démarrée)
```

Pour voir les logs de l'initContainer directement depuis l'UI : clique sur le pod → onglet **Logs** → sélectionne `liquibase-migrations` dans le menu déroulant.

---

## Étape 4 — Workflow GitOps complet : nouvelle migration via Git

C'est le workflow final : **un commit Git = une migration appliquée automatiquement**.

### 4a — Ajouter le changeset dans le ConfigMap

Ouvre `liquibase-configmap.yaml` et ajoute avant `</databaseChangeLog>` :

```xml
        <changeSet id="005" author="a-jacquet">
            <createIndex tableName="users" indexName="idx_users_email">
                <column name="email"/>
            </createIndex>
        </changeSet>
```

### 4b — Pusher sur Git

```powershell
git add liquibase-configmap.yaml
git commit -m "feat: add index on users.email"
git push
```

### 4c — Observer le pipeline

ArgoCD détecte le commit, synchronise le ConfigMap dans le cluster, puis déclenche un rollout du Deployment. L'initContainer Liquibase applique uniquement le changeset 005.

```powershell
kubectl logs -n demo -l app=hello-release-hello -c liquibase-migrations --previous
```

> **Résultat attendu :** `Running Changeset: ...::005::a-jacquet` — uniquement le nouveau.

---

## Récapitulatif du pipeline complet

```
Commit Git (changeset)
    └── ArgoCD détecte (polling 3min ou webhook)
        └── kubectl apply ConfigMap + rollout Deployment
            └── initContainer liquibase-migrations
                ├── Si OK  → app démarre
                └── Si KO  → app bloquée, alerte dans ArgoCD
```

---

## Problèmes courants

| Problème | Cause | Solution |
|---|---|---|
| `Init:CrashLoopBackOff` | Liquibase ne peut pas joindre PostgreSQL | Vérifie l'URL dans `values.yaml` et que le pod postgres tourne |
| `host.docker.internal` ne résout pas | Phase 1 uniquement, réseau Docker | Utilise `host.docker.internal` (Windows/Mac) ou `172.17.0.1` (Linux) |
| Changeset déjà appliqué rejoué | `id` ou `author` modifié dans un changeset existant | Ne jamais modifier un changeset déjà appliqué — crée-en un nouveau |
| `.\mvnw` non reconnu | PowerShell bloque les scripts | `.\mvnw.cmd spring-boot:run` à la place |
| Pod bloqué en `Init:0/1` longtemps | Image `liquibase/liquibase` en cours de téléchargement | Attends 1-2 min, l'image fait ~500 Mo |
