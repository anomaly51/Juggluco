# Kubernetes deployment

This deployment runs one backend replica and keeps its SQLite database on a
`ReadWriteOnce` persistent volume. The service is intentionally `ClusterIP`; expose it
only through an authenticated TLS gateway or a trusted tunnel.

The optional workflow template at
`deploy/github-actions/backend-container.yml` tests the backend and publishes the image
to `ghcr.io/anomaly51/juggluco-backend:latest`. To activate it, copy it to
`.github/workflows/backend-container.yml` using Git credentials authorized to update
GitHub Actions workflows.

The image can also be built and published manually:

```bash
docker build -t ghcr.io/anomaly51/juggluco-backend:latest backend
docker push ghcr.io/anomaly51/juggluco-backend:latest
```

## Install

Generate a distinct backend bearer token and keep it together with the OpenRouter key
outside Git and shell history. One option is to create a local ignored env file and
load it into the Kubernetes Secret:

```bash
kubectl apply -f deploy/kubernetes/namespace.yaml
cp backend/.env.example backend/.env
# Edit backend/.env with newly generated values, then:
kubectl -n juggluco create secret generic juggluco-backend-secrets \
  --from-env-file=backend/.env \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -k deploy/kubernetes
kubectl -n juggluco rollout status deployment/juggluco-backend
```

`secret.example.yaml` documents the required keys but is not included by
`kustomization.yaml`, so placeholder or real credentials cannot be deployed by
accident.

Use two different random credentials: `JUGGLUCO_API_TOKEN` for the Android
writer/admin API and `JUGGLUCO_VIEWER_TOKEN` for the GET-only iOS viewer API.
Both must contain at least 32 characters. Never copy the writer/admin token to
an iPhone.

For remote iOS access, place the service behind a valid HTTPS gateway or trusted
tunnel and add its public host name to `JUGGLUCO_ALLOWED_HOSTS` in
`deployment.yaml`. The included `ClusterIP` service is intentionally not an
Internet-facing endpoint.

For a private, temporary connection from the same computer:

```bash
kubectl -n juggluco port-forward service/juggluco-backend 8765:8765
```

The Android backend URL is then `http://127.0.0.1:8765`. For a physical Android device
connected by USB, also run `adb reverse tcp:8765 tcp:8765`.

## Data and backups

The database lives at `/data/juggluco.db` on `juggluco-backend-data`. Keep the
deployment at one replica: SQLite is not a multi-pod database. Back up the PVC or make
a transactionally consistent SQLite backup before cluster migration. Do not commit a
database snapshot: it contains health-adjacent records.
