[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [ValidatePattern('^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$')]
    [string]$Owner = "fishered",

    [ValidatePattern('^[a-z0-9]+/[a-z0-9]+(?:/[a-z0-9]+)?$')]
    [string]$Platform = "linux/amd64",

    [switch]$PublishLatest,
    [switch]$Push,
    [switch]$SkipBuild,
    [switch]$SkipLogin,
    [switch]$NoPull
)

Set-StrictMode -Version Latest
# Native Docker/Git commands can write warnings and build progress to stderr.
# Their exit codes are checked explicitly below so stderr is not mistaken for
# a terminating PowerShell error.
$ErrorActionPreference = "Continue"

function Assert-Command {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found."
    }
}

function Assert-NativeSuccess {
    param([Parameter(Mandatory = $true)][string]$Action)

    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed with exit code $LASTEXITCODE."
    }
}

function Get-ImageTags {
    param(
        [Parameter(Mandatory = $true)][string]$Image,
        [Parameter(Mandatory = $true)][string]$ReleaseVersion
    )

    $parts = $ReleaseVersion.Split('.')
    $tags = @(
        "${Image}:${ReleaseVersion}",
        "${Image}:$($parts[0]).$($parts[1])"
    )
    if ($PublishLatest) {
        $tags += "${Image}:latest"
    }
    return $tags
}

function Build-Image {
    param(
        [Parameter(Mandatory = $true)][string]$Context,
        [Parameter(Mandatory = $true)][string]$Dockerfile,
        [Parameter(Mandatory = $true)][string[]]$Tags
    )

    $arguments = @(
        "build",
        "--platform", $Platform,
        "--build-arg", "FIREFLY_VERSION=$Version",
        "--file", $Dockerfile
    )
    if (-not $NoPull) {
        $arguments += "--pull"
    }
    foreach ($tag in $Tags) {
        $arguments += @("--tag", $tag)
    }
    $arguments += $Context

    Write-Host "Building $($Tags[0]) for $Platform..."
    & docker @arguments
    Assert-NativeSuccess "Docker build for $($Tags[0])"
}

function Assert-ImageVersion {
    param([Parameter(Mandatory = $true)][string]$Image)

    $actualVersion = (& docker image inspect `
        --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' `
        $Image 2>&1 | Out-String).Trim()
    Assert-NativeSuccess "Inspection of $Image"
    if ($actualVersion -ne $Version) {
        throw "Image '$Image' has OCI version '$actualVersion'; expected '$Version'."
    }
    Write-Host "Validated $Image (OCI version $actualVersion)."
}

function Assert-ImmutableTagAvailable {
    param([Parameter(Mandatory = $true)][string]$Image)

    & docker manifest inspect $Image *> $null
    if ($LASTEXITCODE -eq 0) {
        throw "Immutable release tag '$Image' already exists in GHCR. Refusing to overwrite it."
    }
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDirectory "..\..")).Path
$tag = "v$Version"
$worktreePath = Join-Path ([System.IO.Path]::GetTempPath()) "firefly-ghcr-$Version-$PID"
$worktreeAdded = $false

$serverImage = "ghcr.io/$Owner/firefly"
$adminImage = "ghcr.io/$Owner/firefly-admin"
$serverTags = @(Get-ImageTags -Image $serverImage -ReleaseVersion $Version)
$adminTags = @(Get-ImageTags -Image $adminImage -ReleaseVersion $Version)

Assert-Command "git"
Assert-Command "docker"
if ($Push -and -not $SkipLogin) {
    Assert-Command "gh"
}

& docker info *> $null
Assert-NativeSuccess "Docker Engine availability check"

try {
    $tagType = (& git -C $repoRoot cat-file -t $tag 2>&1 | Out-String).Trim()
    Assert-NativeSuccess "Lookup of Git tag '$tag'"
    if ($tagType -ne "tag") {
        throw "'$tag' must be an annotated Git tag; found object type '$tagType'."
    }

    if (Test-Path -LiteralPath $worktreePath) {
        throw "Temporary worktree path already exists: $worktreePath"
    }

    Write-Host "Creating detached worktree from $tag..."
    & git -C $repoRoot worktree add --detach $worktreePath $tag
    Assert-NativeSuccess "Creation of temporary worktree"
    $worktreeAdded = $true

    $taggedBuildFile = Join-Path $worktreePath "build.gradle"
    $buildText = Get-Content -LiteralPath $taggedBuildFile -Raw
    $versionMatch = [regex]::Match(
        $buildText,
        '(?m)^\s*version\s*=\s*["'']([^"'']+)["'']\s*$'
    )
    if (-not $versionMatch.Success) {
        throw "Could not determine the project version from $taggedBuildFile."
    }
    $projectVersion = $versionMatch.Groups[1].Value
    if ($projectVersion -ne $Version) {
        throw "Git tag '$tag' declares project version '$projectVersion'; expected '$Version'."
    }
    Write-Host "Validated $tag against project version $projectVersion."

    if (-not $SkipBuild) {
        Build-Image `
            -Context $worktreePath `
            -Dockerfile (Join-Path $worktreePath "Dockerfile") `
            -Tags $serverTags
        Build-Image `
            -Context (Join-Path $worktreePath "ui\admin") `
            -Dockerfile (Join-Path $worktreePath "ui\admin\Dockerfile") `
            -Tags $adminTags
    }

    Assert-ImageVersion $serverTags[0]
    Assert-ImageVersion $adminTags[0]

    if (-not $Push) {
        Write-Host "Build and validation complete. No images were pushed."
        Write-Host "Run again with -Push to publish the explicit tags to GHCR."
        return
    }

    if (-not $SkipLogin) {
        & gh auth status --hostname github.com
        Assert-NativeSuccess "GitHub CLI authentication check"

        $account = (& gh api user --jq .login 2>&1 | Out-String).Trim()
        Assert-NativeSuccess "GitHub account lookup"
        if ($account -ine $Owner) {
            throw "GitHub CLI is authenticated as '$account', but GHCR owner is '$Owner'."
        }

        $token = (& gh auth token --hostname github.com 2>&1 | Out-String).Trim()
        Assert-NativeSuccess "GitHub token lookup"
        if ([string]::IsNullOrWhiteSpace($token)) {
            throw "GitHub CLI returned an empty authentication token."
        }
        $token | & docker login ghcr.io --username $Owner --password-stdin
        $token = $null
        Assert-NativeSuccess "GHCR login"
    }

    Assert-ImmutableTagAvailable $serverTags[0]
    Assert-ImmutableTagAvailable $adminTags[0]

    foreach ($image in @($serverTags + $adminTags)) {
        Write-Host "Pushing $image..."
        & docker push $image
        Assert-NativeSuccess "Push of $image"
    }

    Write-Host "Published Firefly $Version images to GHCR."
}
finally {
    if ($worktreeAdded) {
        Write-Host "Removing temporary worktree..."
        & git -C $repoRoot worktree remove --force $worktreePath
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove temporary worktree: $worktreePath"
        }
        & git -C $repoRoot worktree prune
    }
}
