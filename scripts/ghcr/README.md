# Manual GHCR Image Publishing

This directory contains the manual release workflow for the Firefly container
images:

- `ghcr.io/fishered/firefly`
- `ghcr.io/fishered/firefly-admin`

The script builds from an annotated release Git tag in a temporary detached
worktree. It does not switch branches or modify the current worktree. By
default it only builds and validates images; uploading requires the explicit
`-Push` option.

## Prerequisites

- Docker Desktop is installed and the Docker Engine is running.
- The release commit has an annotated Git tag such as `v1.1.3`.
- The version in the tagged `build.gradle` matches the requested version.
- GitHub CLI is installed and authenticated as the package owner.

For first-time package publishing, grant the GitHub CLI token package scopes:

```powershell
gh auth refresh -h github.com -s read:packages,write:packages
gh auth status
```

## Build and Validate Locally

Run this first. It builds both images for `linux/amd64`, assigns the `1.1.3`
and `1.1` tags, and verifies the OCI version label. It does not upload images.

```powershell
.\scripts\ghcr\publish-ghcr.ps1 -Version 1.1.3
```

The initial workflow intentionally publishes `linux/amd64` images only. It is
not a multi-architecture build.

## Publish to GHCR

After checking the local images, publish the version, minor, and `latest` tags:

```powershell
.\scripts\ghcr\publish-ghcr.ps1 `
  -Version 1.1.3 `
  -PublishLatest `
  -Push
```

The script logs in through the active GitHub CLI account without printing its
token. It checks that `gh` is authenticated as `fishered`, refuses to overwrite
an existing immutable `X.Y.Z` release tag, and pushes only the tags listed by
the command. It never uses `docker push --all-tags`.

Use a different GitHub owner when publishing a fork:

```powershell
.\scripts\ghcr\publish-ghcr.ps1 -Version 1.1.3 -Owner another-owner -Push
```

### Reuse Existing Local Images

Use `-SkipBuild` only when both exact version images already exist locally and
their OCI version labels are correct:

```powershell
.\scripts\ghcr\publish-ghcr.ps1 `
  -Version 1.1.3 `
  -PublishLatest `
  -SkipBuild `
  -Push
```

### Reuse an Existing Docker Login

Use `-SkipLogin` when Docker is already authenticated to `ghcr.io` with an
account that can write both packages:

```powershell
.\scripts\ghcr\publish-ghcr.ps1 `
  -Version 1.1.3 `
  -PublishLatest `
  -SkipLogin `
  -Push
```

### Build with Cached Base Images

By default the script passes `--pull` to `docker build` so the release uses the
latest base image metadata for the Dockerfile tag. If Docker Hub is temporarily
unreachable but the base image is already cached locally, use `-NoPull`:

```powershell
.\scripts\ghcr\publish-ghcr.ps1 -Version 1.1.3 -NoPull
```

This is only useful when the required base images already exist on the local
machine. For the current Dockerfiles that means:

```powershell
docker image ls amazoncorretto:21-alpine
```

## Make Packages Public

The first publication may create private packages. For both `firefly` and
`firefly-admin`:

1. Open the GitHub profile for `fishered` and select **Packages**.
2. Open the package and select **Package settings**.
3. In **Danger Zone**, select **Change visibility** and choose **Public**.

Then verify anonymous pulls. This command logs Docker out of GHCR, so run it
only when that is acceptable for the current machine:

```powershell
docker logout ghcr.io
docker pull ghcr.io/fishered/firefly:1.1.3
docker pull ghcr.io/fishered/firefly-admin:1.1.3
```

## Troubleshooting

### Docker Engine is unavailable

Start Docker Desktop and wait until `docker info` succeeds, then rerun the
script.

### Docker Hub base image metadata cannot be loaded

If the build fails while loading metadata for `amazoncorretto:21-alpine`, Docker
cannot reach Docker Hub or its authentication service. Check DNS and Docker
network access first:

```powershell
Resolve-DnsName auth.docker.io
Resolve-DnsName registry-1.docker.io
docker pull amazoncorretto:21-alpine
```

If these commands resolve to unexpected addresses or time out, configure Docker
Desktop to use a working proxy, DNS server, or trusted registry mirror, then
restart Docker Desktop and rerun the script. If the base image is already cached
locally, rerun with `-NoPull`.

### `permission_denied: write_package`

Refresh GitHub CLI package scopes and confirm the active account:

```powershell
gh auth refresh -h github.com -s read:packages,write:packages
gh auth status
```

Also check that the account owns the target namespace or has package write
permission.

### The package remains private

Publishing an image does not automatically make a new package public. Change
the visibility separately for both packages in GitHub package settings.

### The release tag is missing or lightweight

Create and push an annotated release tag from the intended release commit:

```powershell
git tag -a v1.1.3 -m "Release 1.1.3"
git push origin v1.1.3
```

Do not move or recreate a published release tag.

### The requested version differs from Gradle

The script reads `build.gradle` from the tagged commit. Update the project
version, merge it, and create the release tag from that commit. Do not publish
an image whose requested version differs from the tagged project version.

### The immutable version tag already exists

The script intentionally refuses to overwrite `X.Y.Z` tags. Inspect the
existing package before continuing. If a first publication was incomplete,
remove only the incorrect package version in GitHub and rerun the same release
after confirming that no consumer relies on it.
