# CI/CD Plan — Separate Server and Client Image Builds

Status: designed, not built. Planned for tomorrow.

## Goal

One GitHub Actions workflow, triggered on push to `main`, that rebuilds
only the image whose files actually changed — server or client — rather
than rebuilding both every time.

## Why this is non-trivial

`Client.java` and the server files (`Server.java`, `ClientHandler.java`,
etc.) live in the same folder, `src/com/shobhit/Network_lab/`. Path
filtering has to be done file-by-file, not folder-by-folder.

## Approach

Use `dorny/paths-filter` to detect which specific files changed, then run
one job per image, each gated on its own filter.

- **Server job** triggers on: `Dockerfile.server`, and
  `Server.java`, `ClientHandler.java`, `ChatRoom.java`,
  `MessageBroker.java`, `MessageDispatcher.java`, `MessageType.java`,
  `MetricsSubscriber.java`, `MeshEventServer.java`,
  `RedisClientRegistry.java`, `ServerMetrics.java`.
- **Client job** triggers on: `Dockerfile.client`, `Client.java`,
  and everything under `peer-app/`.

Each job builds its image, tags it both `:latest` and `:${{ github.sha }}`
(the commit hash), and pushes both tags to Docker Hub.

## Why tag with the commit SHA too, not just `latest`

`kubectl apply` doesn't create new pods if the YAML text hasn't changed,
even if `:latest` now points to different bytes on Docker Hub — this is
why manual `kubectl delete pods` has been needed after every rebuild in
this project so far. Tagging with a unique value (the commit SHA) and
referencing that tag in the deployment YAML would make `kubectl apply`
see a genuinely different image reference and create new pods
automatically, with no manual restart step.

## Setup still needed before this can run

- [ ] A Docker Hub access token stored as a GitHub Actions secret named
      `DOCKERHUB_TOKEN`.
- [ ] Decide whether the workflow also deploys to the EC2 cluster
      automatically (needs cluster credentials as a secret — a real
      security decision to make deliberately) or whether pushing the
      image is as far as automation goes for now, with `kubectl` still
      run by hand afterward.

## Not decided yet

- Whether to move the deployment YAMLs to use the commit-SHA tag instead
  of `latest`, and how that interacts with the manual redeploy workflow
  used throughout this project so far.
- Whether Jenkins is ever worth adopting for this project — reasoned
  through separately; conclusion was no, not at this project's current
  scale, since code and CI both already live on GitHub and there's no
  compliance, custom-hardware, or multi-team need driving it.
