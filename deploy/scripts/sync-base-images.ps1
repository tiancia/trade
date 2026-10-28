#requires -Version 5.1
<#
.SYNOPSIS
Mirrors the five fixed Trade build base images to the project's Tencent TCR registry.
.DESCRIPTION
Run explicitly on a machine that can pull Docker Hub images, after docker login
to ccr.ccs.tencentyun.com. This script never reads or stores registry credentials.
Pushes linux/amd64 images only; does not build or deploy the Trade application.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$registry = 'ccr.ccs.tencentyun.com/tiancia-trade'
$images = @(
    @{ Source = 'docker.io/library/maven:3.9.14-eclipse-temurin-21'; Destination = 'base-maven:3.9.14-eclipse-temurin-21' }
    @{ Source = 'docker.io/library/eclipse-temurin:21-jre-jammy'; Destination = 'base-eclipse-temurin:21-jre-jammy' }
    @{ Source = 'docker.io/library/node:22.23.3-bookworm-slim'; Destination = 'base-node:22.23.3-bookworm-slim' }
    @{ Source = 'docker.io/library/nginx:1.30.6-alpine'; Destination = 'base-nginx:1.30.6-alpine' }
    @{ Source = 'docker.io/library/alpine:3.22'; Destination = 'base-alpine:3.22' }
)

foreach ($entry in $images) {
    $source = $entry.Source
    $destination = "$registry/$($entry.Destination)"
    Write-Host "Mirroring $source -> $destination"
    & docker pull --platform linux/amd64 $source
    if ($LASTEXITCODE -ne 0) { throw "docker pull failed for $source (exit $LASTEXITCODE)" }
    & docker tag $source $destination
    if ($LASTEXITCODE -ne 0) { throw "docker tag failed for $source (exit $LASTEXITCODE)" }
    & docker push $destination
    if ($LASTEXITCODE -ne 0) { throw "docker push failed for $destination (exit $LASTEXITCODE)" }
}
Write-Host 'All five base images have been mirrored. You may now enable USE_PRIVATE_BASE_IMAGES=true in CNB.'
