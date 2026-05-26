# TP complet — Helm + ArgoCD GitOps sur Windows

> **Prérequis :** Docker Desktop + Kubernetes activé, kubectl, Helm, ArgoCD dans le namespace `argocd`.
> Toutes les commandes s'exécutent dans **PowerShell sur Windows**.

---

# PHASE 1 — Déployer avec Helm seul

## Étape 1 — Créer le namespace "demo"

```powershell
kubectl create namespace demo
```

Crée un espace de noms isolé dans Kubernetes pour cette app.

**Vérification :**

```powershell
kubectl get namespaces
```

> Tu dois voir `demo` dans la liste avec le statut `Active`.

---

## Étape 2 — Créer le Chart Helm

On crée la structure de dossiers et les fichiers du chart manuellement.

```powershell
# Créer la structure de dossiers
New-Item -ItemType Directory -Path "hello-chart\templates" -Force
```

### `hello-chart\Chart.yaml`

```powershell
@'
apiVersion: v2
name: hello-app
description: App de démonstration nginx
type: application
version: 0.1.0
appVersion: "latest"
'@ | Set-Content -Path "hello-chart\Chart.yaml" -Encoding UTF8
```

### `hello-chart\values.yaml`

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
'@ | Set-Content -Path "hello-chart\values.yaml" -Encoding UTF8
```

### `hello-chart\templates\deployment.yaml`

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
      containers:
        - name: hello
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.containerPort }}
'@ | Set-Content -Path "hello-chart\templates\deployment.yaml" -Encoding UTF8
```

### `hello-chart\templates\service.yaml`

```powershell
@'
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}-hello
  namespace: {{ .Release.Namespace }}
spec:
  selector:
    app: {{ .Release.Name }}-hello
  ports:
    - protocol: TCP
      port: {{ .Values.service.port }}
      targetPort: {{ .Values.containerPort }}
  type: {{ .Values.service.type }}
'@ | Set-Content -Path "hello-chart\templates\service.yaml" -Encoding UTF8
```

**Vérification — valider la syntaxe du chart :**

```powershell
helm lint hello-chart
```

> **Résultat attendu :** `1 chart(s) linted, 0 chart(s) failed`

---

## Étape 3 — Installer le chart dans le namespace "demo"

```powershell
helm install hello-release hello-chart --namespace demo
```

Déploie le chart Helm dans le namespace `demo` sous le nom de release `hello-release`.

**Vérification :**

```powershell
helm list -n demo
```

> **Résultat attendu :**
> ```
> NAME            NAMESPACE  STATUS    CHART          APP VERSION
> hello-release   demo       deployed  hello-app-0.1.0  latest
> ```

---

## Étape 4 — Vérifier que le pod tourne

```powershell
kubectl get pods -n demo
```

> **Résultat attendu :**
> ```
> NAME                                  READY   STATUS    RESTARTS   AGE
> hello-release-hello-xxxxxxxxx-xxxxx   1/1     Running   0          30s
> ```

> **Si le statut est `ImagePullBackOff` :** l'image n'a pas pu être téléchargée. Vérifie ta connexion internet et que Docker Desktop est bien démarré. Si l'erreur contient `insufficient_scope`, l'image source est privée — `nginx:alpine` (image officielle) évite ce problème.

---

## Étape 5 — Exposer l'app en local

> **Avertissement Windows :** le port 8080 est souvent réservé par Hyper-V. Utilise directement 8888 pour éviter le problème.

```powershell
kubectl port-forward svc/hello-release-hello -n demo 8888:80
```

Ouvre **http://localhost:8888** dans ton navigateur.

> **Résultat attendu :** page "Hello World" avec un fond coloré affichant le nom du serveur et l'IP.

Ctrl+C pour arrêter le port-forward quand tu as terminé.

---

## Étape 6 — Mise à jour : passer à 2 réplicas

```powershell
helm upgrade hello-release hello-chart --namespace demo --set replicaCount=2
```

Met à jour la release Helm en surchargeant la valeur `replicaCount` sans modifier `values.yaml`.

**Vérification :**

```powershell
kubectl get pods -n demo
```

> **Résultat attendu :** 2 pods `Running`.

Vérifie aussi l'historique Helm :

```powershell
helm history hello-release -n demo
```

> Tu dois voir 2 révisions : `1` (install) et `2` (upgrade).

---

## Étape 7 — Rollback à la révision précédente

```powershell
helm rollback hello-release 1 -n demo
```

Revient à la révision 1 du chart (1 réplica).

**Vérification :**

```powershell
kubectl get pods -n demo
helm history hello-release -n demo
```

> Tu dois voir 1 seul pod, et une révision `3` de type `rollback` dans l'historique.

---
---

# PHASE 2 — Pipeline GitOps complet avec ArgoCD

> **Dépendance :** cette phase utilise le chart créé en Phase 1. Assure-toi que le dossier `hello-chart/` existe bien.

---

## Étape 1 — Pousser le chart sur GitHub

### 1a — Installer GitHub CLI (si pas déjà fait)

```powershell
winget install GitHub.cli
```

Ferme et rouvre le terminal, puis authentifie-toi :

```powershell
gh auth login
```

Suis les instructions : choisis `GitHub.com` → `HTTPS` → `Login with a web browser`.

### 1b — Initialiser le repo Git local

```powershell
# Se placer au niveau du dossier hello-chart (pas dedans)
git init
git add hello-chart/
git commit -m "feat: add hello-app helm chart"
```

> **Avertissement :** si c'est ton premier commit Git sur cette machine, Git peut te demander de configurer ton identité :
> ```powershell
> git config --global user.email "ton@email.com"
> git config --global user.name "Ton Nom"
> ```
> Relance ensuite le `git commit`.

### 1c — Créer le repo GitHub et pousser

```powershell
gh repo create helm-argocd-demo --public --source=. --remote=origin --push
```

Crée un repo public `helm-argocd-demo` sur ton compte GitHub, l'associe au repo local et pousse le code.

**Vérification :**

```powershell
gh repo view --web
```

> Ouvre le repo dans le navigateur. Tu dois voir le dossier `hello-chart/` avec ses fichiers.

> **Note :** garde l'URL du repo sous la main, elle ressemble à `https://github.com/TON_USERNAME/helm-argocd-demo.git`. Tu en auras besoin à l'étape suivante.

---

## Étape 2 — Créer l'Application ArgoCD

### 2a — Générer le fichier YAML de l'Application

> **Remplace `TON_USERNAME`** par ton vrai nom d'utilisateur GitHub dans la commande ci-dessous.

```powershell
@'
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: hello-app
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/TON_USERNAME/helm-argocd-demo.git
    targetRevision: HEAD
    path: hello-chart
  destination:
    server: https://kubernetes.default.svc
    namespace: demo
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
'@ | Set-Content -Path "argocd-app.yaml" -Encoding UTF8
```

> **`prune: true`** : ArgoCD supprime les ressources retirées du Git.
> **`selfHeal: true`** : ArgoCD corrige automatiquement toute dérive manuelle (utile à l'étape 5).

### 2b — Appliquer l'Application dans le cluster

```powershell
kubectl apply -f argocd-app.yaml
```

Déclare l'application dans ArgoCD. ArgoCD va immédiatement synchroniser le repo Git avec le cluster.

**Vérification :**

```powershell
kubectl get application -n argocd
```

> **Résultat attendu :**
> ```
> NAME        SYNC STATUS   HEALTH STATUS
> hello-app   Synced        Healthy
> ```

---

## Étape 3 — Vérifier dans l'UI ArgoCD

> **Rappel :** si le port 8080 est bloqué chez toi (voir le guide précédent), utilise 8443.

Dans un **second terminal PowerShell** :

```powershell
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

Ouvre **https://localhost:8443** (accepte le certificat auto-signé).

Récupère le mot de passe admin si besoin :

```powershell
kubectl get secret argocd-initial-admin-secret -n argocd -o jsonpath="{.data.password}" | % { [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($_)) }
```

> **Dans l'UI :** tu dois voir la carte `hello-app` avec les badges **Synced** (vert) et **Healthy** (vert). Clique dessus pour voir le graphe du déploiement (Deployment → ReplicaSet → Pods → Service).

---

## Étape 4 — Workflow GitOps : modifier les réplicas via Git

C'est ici que la magie GitOps opère : **Git est la seule source de vérité**.

### 4a — Modifier `values.yaml` localement

Ouvre `hello-chart\values.yaml` et change `replicaCount: 1` en `replicaCount: 3` :

```powershell
(Get-Content "hello-chart\values.yaml") -replace "replicaCount: 1", "replicaCount: 3" | Set-Content "hello-chart\values.yaml" -Encoding UTF8
```

### 4b — Pousser le changement sur GitHub

```powershell
git add hello-chart/values.yaml
git commit -m "scale: increase replicas to 3"
git push
```

### 4c — Observer la synchronisation ArgoCD

ArgoCD vérifie le repo toutes les **3 minutes** par défaut. Pour forcer une sync immédiate :

```powershell
kubectl patch application hello-app -n argocd --type merge -p '{"operation":{"initiatedBy":{"username":"admin"},"sync":{"revision":"HEAD"}}}'
```

Ou depuis l'UI : bouton **Sync** → **Synchronize**.

**Vérification :**

```powershell
kubectl get pods -n demo
```

> **Résultat attendu :** 3 pods `Running` — sans que tu aies touché à `kubectl` ou `helm` directement.

---

## Étape 5 — Simuler une dérive (selfHeal)

On modifie manuellement le déploiement pour simuler une intervention humaine non désirée.

```powershell
kubectl scale deployment hello-release-hello --replicas=1 -n demo
```

Réduit manuellement les réplicas à 1, en dehors de Git.

**Observe la correction automatique par ArgoCD :**

```powershell
# Attends 30-60 secondes puis vérifie
kubectl get pods -n demo -w
```

> ArgoCD détecte la dérive (l'état réel ne correspond plus à Git) et remet automatiquement 3 réplicas.
> Dans l'UI, tu verras brièvement le statut passer à **OutOfSync** puis revenir à **Synced**.

Ctrl+C pour quitter le watch.

---

## Étape 6 — Historique et rollback depuis l'UI ArgoCD

### Via l'UI

1. Dans l'UI ArgoCD, ouvre l'app `hello-app`
2. Clique sur **History and Rollback** (icône horloge en haut à droite)
3. Tu vois la liste des déploiements avec leur commit Git associé
4. Clique sur `...` à côté d'une révision précédente → **Rollback**

### Via kubectl (alternative CLI)

```powershell
# Lister l'historique ArgoCD
kubectl get application hello-app -n argocd -o jsonpath='{.status.history}' | ConvertFrom-Json | Select-Object revision, deployedAt
```

> **Note :** le rollback ArgoCD crée un déploiement pointant vers un commit Git précis. C'est différent d'un `helm rollback` — ici la source de vérité reste Git.

---

## Récapitulatif final

```powershell
# État du cluster
kubectl get pods -n demo
kubectl get svc -n demo

# État ArgoCD
kubectl get application -n argocd

# Historique Helm (Phase 1)
helm history hello-release -n demo
```

---

## Problèmes courants

| Problème | Cause | Solution |
|---|---|---|
| `ImagePullBackOff` | Image non trouvable | Vérifie connexion internet, Docker Desktop démarré |
| ArgoCD app en `Unknown` | Repo Git inaccessible | Vérifie que le repo est bien **public** sur GitHub |
| Sync ne se déclenche pas | Délai de polling (3 min) | Clique **Sync** dans l'UI ou utilise le `kubectl patch` de l'étape 4c |
| `selfHeal` ne corrige pas | `selfHeal` non activé | Vérifie que `argocd-app.yaml` contient bien `selfHeal: true` et réapplique |
| Port 8443 bloqué | Windows port exclusion | Essaie 9090 : `kubectl port-forward svc/argocd-server -n argocd 9090:443` |
