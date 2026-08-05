# Builds the Apricorn Harvester mod and copies the jar to dist/.
$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "Building Apricorn Harvester..." -ForegroundColor Cyan
& "$ProjectRoot\gradlew.bat" build
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed."
    exit $LASTEXITCODE
}

$BuiltJar = Get-ChildItem -Path "$ProjectRoot\build\libs" -Filter "*.jar" |
    Where-Object { $_.Name -notmatch '-sources|-javadoc' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $BuiltJar) {
    Write-Error "No jar found in build\libs after build."
    exit 1
}

$DistDir = Join-Path $ProjectRoot "dist"
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$OutputJar = Join-Path $DistDir $BuiltJar.Name
Copy-Item -Path $BuiltJar.FullName -Destination $OutputJar -Force

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "Jar ready for your mods folder:" -ForegroundColor Green
Write-Host "  $OutputJar"
Write-Host ""
Write-Host "Also requires in mods:" -ForegroundColor Yellow
Write-Host "  - baritone-api-neoforge-1.11.2.jar"
Write-Host "  - Pixelmon 9.3.x for NeoForge 1.21.1"
