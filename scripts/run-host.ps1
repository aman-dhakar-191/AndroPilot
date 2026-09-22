<#
.SYNOPSIS
    Starts the AndroPilot host with a token that survives restarts.

.DESCRIPTION
    The host generates a random token when it is not given one, which means every restart
    invalidates the endpoint saved on the phone. This keeps one in .andropilot/token and
    reuses it, so the phone is configured once and reconnects on its own afterwards.

    It also prints the address to type into the agent app, resolved from the adapter that
    actually has a default gateway rather than the first one Windows happens to list.

.EXAMPLE
    .\scripts\run-host.ps1
    Bridge and telemetry ingest, no model.

.EXAMPLE
    $env:ANDROPILOT_MODEL_KEY = "sk-or-v1-..."
    .\scripts\run-host.ps1 -ModelEndpoint https://openrouter.ai/api/v1 -Model vendor/model-name
    Adds the agent loop, so the host can drive the phone.

.EXAMPLE
    .\scripts\run-host.ps1 -NoBuild
    Skips the Gradle step when nothing has changed.
#>
[CmdletBinding()]
param(
    [int]$Port = 8765,
    [int]$IngestPort = 8766,

    # 0.0.0.0 because the phone has to reach this across the LAN. The token is what stands
    # between that and anyone else on the network, which is exactly why it must be a real
    # one and not left to a default.
    [string]$Bind = "0.0.0.0",

    [string]$ModelEndpoint = $env:ANDROPILOT_MODEL_ENDPOINT,
    [string]$Model = $(if ($env:ANDROPILOT_MODEL) { $env:ANDROPILOT_MODEL } else { "gpt-4o-mini" }),

    # Serves the control page, always on 127.0.0.1 whatever -Bind says.
    [int]$UiPort = 0,

    [switch]$Mcp,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- token ------------------------------------------------------------------------------
$secretsDir = Join-Path $root ".andropilot"
$tokenFile = Join-Path $secretsDir "token"
if (-not (Test-Path $secretsDir)) { New-Item -ItemType Directory -Path $secretsDir | Out-Null }
if (-not (Test-Path $tokenFile)) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    # Base64url: a '+' or '/' in a token that also travels as a ?token= query parameter is
    # an escaping bug waiting to happen.
    $token = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
    Set-Content -Path $tokenFile -Value $token -NoNewline
    Write-Host "Generated a new token and saved it to .andropilot\token" -ForegroundColor Yellow
}
$token = (Get-Content -Path $tokenFile -Raw).Trim()

# ---- build ------------------------------------------------------------------------------
$launcher = Join-Path $root "host\build\install\andropilot-host\bin\andropilot-host.bat"
if (-not $NoBuild -or -not (Test-Path $launcher)) {
    Write-Host "Building the host..." -ForegroundColor Cyan
    & (Join-Path $root "gradlew.bat") :andropilot-host:installDist --quiet
    if ($LASTEXITCODE -ne 0) { throw "The host did not build." }
}
if (-not (Test-Path $launcher)) { throw "No launcher at $launcher" }

# ---- where the phone should point --------------------------------------------------------
# The adapter with a default gateway, not merely the first one: a machine with WSL, Hyper-V
# or a VPN has several addresses and only one of them is on the network the phone is on.
$lan = $null
try {
    $lan = (Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
        Select-Object -First 1).IPv4Address.IPAddress
} catch {
    $lan = $null
}
if (-not $lan) { $lan = "<this machine's LAN address>" }

# ---- run --------------------------------------------------------------------------------
$hostArgs = @(
    "--bind", $Bind,
    "--port", $Port,
    "--token", $token,
    "--skills", (Join-Path $root "skills"),
    "--ingest-port", $IngestPort
)
if ($UiPort -gt 0) { $hostArgs += @("--ui-port", $UiPort) }
if ($Mcp) { $hostArgs += "--mcp" }
if ($ModelEndpoint) {
    $hostArgs += @("--model-endpoint", $ModelEndpoint, "--model", $Model)
    if (-not $env:ANDROPILOT_MODEL_KEY) {
        Write-Host "ANDROPILOT_MODEL_KEY is not set; the model endpoint will refuse the request." -ForegroundColor Yellow
    }
}
# The key is deliberately NOT passed as an argument. The host reads it from the environment
# it inherits, and an argument is readable by anything that can list processes.

Write-Host ""
Write-Host "  Host endpoint for the agent app:  ws://${lan}:${Port}/agent" -ForegroundColor Green
Write-Host "  Shared token:                     $token" -ForegroundColor Green
if ($UiPort -gt 0) { Write-Host "  Control page:                     http://127.0.0.1:$UiPort" -ForegroundColor Green }
Write-Host ""

& $launcher @hostArgs
