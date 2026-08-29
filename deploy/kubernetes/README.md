# Kubernetes deployment

The production chart in `deploy/kubernetes/chart` runs one FastAPI replica and serves
the React PWA from the same image. SQLite stores its data on a retained `ReadWriteOnce`
volume.

## Delivery flow

`.github/workflows/backend-container.yml` checks the PWA, backend, and combined image
on pull requests. A push to `primary` runs the same checks, publishes
`ghcr.io/anomaly51/juggluco-backend:sha-<commit>`, and tests an anonymous image pull.

After those checks pass, the workflow copies the tested chart to the `deploy` branch,
writes the immutable tag to `deploy/kubernetes/chart/values.yaml`, and commits the
promotion. The Argo CD application in `anomaly51/general-1-argocd` tracks that branch
and syncs it into the `juggluco` namespace. The workflow uses its repository
`GITHUB_TOKEN` and needs no Actions secrets.

GitHub creates a new GHCR package with private visibility. After the first publish,
set `juggluco-backend` to Public in the package settings and rerun the workflow. The
anonymous pull check blocks promotion until the cluster can fetch the image.

The copy under `deploy/github-actions/` mirrors the active workflow for forks that
cannot commit `.github/workflows/` during setup.

## Runtime secrets

The chart asks Vault Secrets Operator for these keys from `kv/apps/juggluco`:

- `JUGGLUCO_API_TOKEN`: Android writer and admin API token, at least 32 characters.
- `JUGGLUCO_VIEWER_TOKEN`: read-only PWA token, 32 to 512 URL-safe ASCII characters.
- `OPENROUTER_API_KEY`: server-side audio forecast key.

Create a Kubernetes-auth Vault role named `juggluco` for the
`juggluco/juggluco` service account and grant it read access to that path. Keep all
three values out of Git,
container build arguments, and GitHub Actions. Revoke any OpenRouter key exposed in a
chat or shell history before adding a replacement to Vault.

## Production URLs

- API: `https://juggluco-general1.api-api-api.com/v1/`
- Health: `https://juggluco-general1.api-api-api.com/v1/health`
- Installed PWA: `https://juggluco-general1.api-api-api.com/viewer/`

Set the Android backend URL to `https://juggluco-general1.api-api-api.com` and enter
the `JUGGLUCO_API_TOKEN` in the app settings. Enter the viewer token only in the PWA.

## Local or standalone cluster

The Kustomize manifests beside this README remain available for a cluster that does
not use the production Argo CD chart. Do not apply them to General-1; Argo CD owns that
cluster.

```bash
kubectl apply -f deploy/kubernetes/namespace.yaml
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
