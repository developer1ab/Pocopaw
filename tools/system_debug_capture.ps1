param(
    [string]$Device,
    [string]$AppId = "com.atombits.pocopaw",
    [string]$OutputDir = ".\logs\captures",
    [int]$Minutes = 5,
    [string]$JdPackage = "com.jingdong.app.mall",
    [switch]$NoUiDump,
    [switch]$NoScreenshot
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$nodeScript = Join-Path $repoRoot "scripts\system-debug-capture.js"
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue

if (-not $nodeCommand) {
    Write-Error "node was not found in PATH."
    exit 1
}

$arguments = @(
    $nodeScript,
    "--app-id", $AppId,
    "--output-dir", $OutputDir,
    "--minutes", "$Minutes",
    "--jd-package", $JdPackage
)

if ($Device) {
    $arguments += @("--device", $Device)
}
if ($NoUiDump) {
    $arguments += "--no-ui-dump"
}
if ($NoScreenshot) {
    $arguments += "--no-screenshot"
}

& $nodeCommand.Source @arguments
exit $LASTEXITCODE