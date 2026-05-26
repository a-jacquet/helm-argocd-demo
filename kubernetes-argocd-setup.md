# Guide complet : Kubernetes + Helm + ArgoCD sur Windows

> **Prérequis :** Docker Desktop installé et **démarré en arrière-plan** (icône dans la barre des tâches).
> **Toutes les commandes de ce guide s'exécutent dans PowerShell sur Windows**, jamais dans Docker directement.
> Recommandation : utiliser **kind** (Kubernetes in Docker) plutôt que le Kubernetes intégré à Docker Desktop — plus stable et plus proche d'un vrai cluster.

---

> **Comment ça marche :** kind utilise Docker Desktop en arrière-plan pour créer des containers qui jouent le rôle de nœuds Kubernetes. Tu lances tout depuis PowerShell, Docker fait le travail en coulisses.

---

## Étape 1 — Installer kind et kubectl

```powershell
# Installer kind via winget
winget install Kubernetes.kind

# Installer kubectl via winget
winget install Kubernetes.kubectl
```

Ferme et rouvre ton terminal PowerShell, puis vérifie :

```powershell
kind version
kubectl version --client
```

> **Résultat attendu :** les deux affichent un numéro de version sans erreur.

---

## Étape 2 — Créer le cluster Kubernetes local

```powershell
kind create cluster --name local-cluster
```

Crée un cluster single-node dans Docker (~2 min). Kind configure automatiquement kubectl pour pointer dessus.

**Vérification :**

```powershell
kubectl get nodes
```

> **Résultat attendu :**
> ```
> NAME                         STATUS   ROLES           AGE   VERSION
> local-cluster-control-plane  Ready    control-plane   1m    v1.xx.x
> ```

---

## Étape 3 — Installer Helm

```powershell
winget install Helm.Helm
```

Ferme et rouvre le terminal, puis :

```powershell
helm version
```

> **Résultat attendu :** `version.BuildInfo{Version:"v3.x.x", ...}`

---

## Étape 4 — Installer ArgoCD via Helm

```powershell
# Ajouter le repo Helm officiel ArgoCD
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update

# Créer le namespace argocd
kubectl create namespace argocd

# Installer ArgoCD
helm install argocd argo/argo-cd --namespace argocd
```

Attend que tous les pods démarrent (~1-2 min) :

```powershell
kubectl get pods -n argocd -w
```

Ctrl+C pour quitter le watch.

> **Résultat attendu :**
> ```
> argocd-server-xxxx                    1/1     Running
> argocd-repo-server-xxxx               1/1     Running
> argocd-application-controller-xxxx    1/1     Running
> ...
> ```

---

## Étape 5 — Exposer l'UI ArgoCD en port-forward

> **Avertissement :** cette commande bloque le terminal. Ouvre un **second terminal PowerShell** pour les étapes suivantes.

> **Problème courant Windows — port 8080 bloqué :** Windows/Hyper-V réserve certaines plages de ports (dont souvent 8080), même pour les admins. Pour vérifier : `netsh interface ipv4 show excludedportrange protocol=tcp`. Si 8080 est listé, utilise 8443 à la place.

```powershell
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

Si 8443 est aussi bloqué, essaie 9090 :

```powershell
kubectl port-forward svc/argocd-server -n argocd 9090:443
```

Ouvre ton navigateur sur : **https://localhost:8443** (ou le port choisi)

Le certificat est auto-signé → clique sur "Continuer quand même".

---

## Étape 6 — Récupérer le mot de passe admin

**Dans le second terminal :**

```powershell
kubectl get secret argocd-initial-admin-secret -n argocd -o jsonpath="{.data.password}" | % { [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($_)) }
```

Connecte-toi sur **https://localhost:8080** avec :
- **Login :** `admin`
- **Password :** le mot de passe récupéré ci-dessus

---

## Étape 7 — Déployer une app de test via ArgoCD

Sauvegarde le contenu suivant dans un fichier `app.yaml` :

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: guestbook
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/argoproj/argocd-example-apps.git
    targetRevision: HEAD
    path: guestbook
  destination:
    server: https://kubernetes.default.svc
    namespace: guestbook
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

Puis applique-le :

```powershell
kubectl apply -f app.yaml
```

**Vérification :**

```powershell
kubectl get application -n argocd
```

> **Résultat attendu :**
> ```
> NAME        SYNC STATUS   HEALTH STATUS
> guestbook   Synced        Healthy
> ```

Dans l'UI ArgoCD (https://localhost:8080), tu verras l'app `guestbook` avec un graphe de déploiement en temps réel.

---

## Vérifications finales

```powershell
kubectl get nodes                  # cluster OK
kubectl get pods -n argocd        # ArgoCD OK
kubectl get application -n argocd  # app déployée
kubectl get pods -n guestbook      # pods de l'app
```

---

## Problèmes courants

| Problème | Cause | Solution |
|---|---|---|
| `kind` non trouvé après install | PATH pas rechargé | Redémarre le terminal |
| Pods en `Pending` | Docker manque de RAM | Docker Desktop > Settings > Resources > au moins 4 Go RAM |
| `port-forward` se coupe | Timeout Kubernetes | Relance la commande port-forward |
| ArgoCD app en `OutOfSync` | Normal au premier déploiement | Clique "Sync" dans l'UI ou attends l'auto-sync |
