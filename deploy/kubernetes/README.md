# Kubernetes deployment

The production chart in `deploy/kubernetes/chart` runs one FastAPI replica and serves
the React PWA from the same image. SQLite stores its data on a retained `ReadWriteOnce`
volume.

## Delivery flow

`.github/workflows/backend-container.yml` checks the PWA, backend, chart, and combined
image on GitHub-hosted runners. After a successful push check on `primary`,
`.github/workflows/backend-deploy.yml` runs only on the restricted
`juggluco-deploy` runner inside Kubernetes. Its local BuildKit publishes
`harbor.internal.api-api-api.com/applications/juggluco:sha-<commit>` to the private
Harbor instance in General-1. Pull requests never run on the cluster runner.

The deployment workflow copies the tested chart to the `deploy` branch, writes the
immutable tag to `deploy/kubernetes/chart/values.yaml`, and commits the promotion.
The Argo CD application in `anomaly51/general-1-argocd` tracks that branch and syncs
it into the `juggluco` namespace. `GITHUB_TOKEN` writes the deployment branch.
`HARBOR_USERNAME` and `HARBOR_PASSWORD` hold the scoped Harbor push account. The
workload gets its registry credential from `kv/apps/registry` through Vault Secrets
Operator.

The copies under `deploy/github-actions/` mirror both active workflows for forks that
cannot commit `.github/workflows/` during setup.

## Runtime secrets

The chart asks Vault Secrets Operator for these keys from `kv/apps/juggluco`:

- `JUGGLUCO_API_TOKEN`: Android writer and admin API token, at least 32 characters.
- `JUGGLUCO_VIEWER_TOKEN`: read-only PWA token, 32 to 512 URL-safe ASCII characters.
- `OPENROUTER_API_KEY`: server-side audio forecast key.

Create a Kubernetes-auth Vault role named `juggluco` for the
`juggluco/juggluco` service account. Grant it read access to `kv/apps/juggluco` and
`kv/apps/registry`. Keep all three runtime values out of Git,
container build arguments, and GitHub Actions. Revoke any OpenRouter key exposed in a
chat or shell history before adding a replacement to Vault.

## Production URLs

- API: `https://juggluco-general1.api-api-api.com/v1/`
- Health: `https://juggluco-general1.api-api-api.com/v1/health`
- Installed PWA: `https://juggluco-general1.api-api-api.com/viewer/`

Fresh Android installs default to `https://juggluco-general1.api-api-api.com`. Enter
the `JUGGLUCO_API_TOKEN` in the app settings; it is deliberately not compiled into
the APK. Enter the viewer token only in the PWA.

## Local or standalone cluster

The Kustomize manifests beside this README remain available for a cluster that does
not use the production Argo CD chart. Do not apply them to General-1; Argo CD owns that
cluster. They reference a tested image in the private General-1 Harbor, so create the
`juggluco-registry` pull secret in the standalone namespace before applying them.

```bash
kubectl apply -f deploy/kubernetes/namespace.yaml
# Provision the juggluco-registry dockerconfigjson Secret from your secret manager.
cp backend/.env.example backend/.env
# Edit backend/.env, then create the standalone-cluster Secret.
kubectl -n juggluco create secret generic juggluco-backend-secrets \
  --from-env-file=backend/.env \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -k deploy/kubernetes
kubectl -n juggluco rollout status deployment/juggluco-backend
```

`secret.example.yaml` lists the required keys but `kustomization.yaml` excludes it.
For a private USB test, forward the service and connect the phone to the same port:

```bash
kubectl -n juggluco port-forward service/juggluco-backend 8765:8765
adb reverse tcp:8765 tcp:8765
```

## Data and backups

Keep one replica because SQLite cannot coordinate writes from multiple pods. The
production chart uses node-local storage because SQLite WAL cannot run safely on NFS.
Back up the PVC or run a transactionally consistent SQLite backup before moving the
workload. Database snapshots contain health records and must stay outside Git.
