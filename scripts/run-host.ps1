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

# ---- settings ---------------------------------------------------------------------------
# Kept in the home directory, not beside the checkout: the token is in it, and a token in
# the working copy is lost the moment the repository is moved or re-cloned -- which means
# reconfiguring the phone by hand.
$settingsDir = Join-Path $HOME ".andropilot-host"
$settingsFile = Join-Path $settingsDir "settings.json"
if (-not (Test-Path $settingsDir)) { New-Item -ItemType Directory -Path $settingsDir | Out-Null }

$settings = if (Test-Path $settingsFile) {
    Get-Content -Path $settingsFile -Raw | ConvertFrom-Json
} else {
    [PSCustomObject]@{}
}

if (-not $settings.token) {
    # Carry over a token from the old location before inventing one. Generating a fresh
    # token here would silently invalidate whatever the phone already has saved.
    $legacy = Join-Path $root ".andropilot\token"
    $token = if (Test-Path $legacy) {
        Write-Host "Moving your existing token into $settingsFile" -ForegroundColor Yellow
        (Get-Content -Path $legacy -Raw).Trim()
    } else {
        $bytes = New-Object byte[] 24
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        # Base64url: a '+' or '/' in a token that also travels as a ?token= query parameter
        # is an escaping bug waiting to happen.
        Write-Host "Generated a new token and saved it to $settingsFile" -ForegroundColor Yellow
        [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
    }
    $settings | Add-Member -NotePropertyName token -NotePropertyValue $token -Force
    $settings | ConvertTo-Json -Depth 5 | Set-Content -Path $settingsFile -NoNewline
}
$token = $settings.token

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
# Only what was explicitly asked for on this run. The host reads the settings file itself,
# and passing a switch's default here would override a value the file deliberately sets.
$hostArgs = @()
if ($PSBoundParameters.ContainsKey('Bind')) { $hostArgs += @("--bind", $Bind) }
if ($PSBoundParameters.ContainsKey('Port')) { $hostArgs += @("--port", $Port) }
if ($PSBoundParameters.ContainsKey('IngestPort')) { $hostArgs += @("--ingest-port", $IngestPort) }
if ($PSBoundParameters.ContainsKey('UiPort') -and $UiPort -gt 0) { $hostArgs += @("--ui-port", $UiPort) }
if ($PSBoundParameters.ContainsKey('ModelEndpoint') -and $ModelEndpoint) {
    $hostArgs += @("--model-endpoint", $ModelEndpoint)
}
if ($PSBoundParameters.ContainsKey('Model')) { $hostArgs += @("--model", $Model) }
if ($Mcp) { $hostArgs += "--mcp" }

# Defaults the file does not carry, so a fresh machine still works out of the box.
if (-not $settings.bind -and -not $PSBoundParameters.ContainsKey('Bind')) { $hostArgs += @("--bind", $Bind) }
if (-not $settings.skills) { $hostArgs += @("--skills", (Join-Path $root "skills")) }
if (-not $settings.ingestPort -and -not $PSBoundParameters.ContainsKey('IngestPort')) {
    $hostArgs += @("--ingest-port", $IngestPort)
}
# The key is deliberately NOT passed as an argument. The host reads it from the environment
# it inherits, and an argument is readable by anything that can list processes.

Write-Host ""
Write-Host "  Host endpoint for the agent app:  ws://${lan}:${Port}/agent" -ForegroundColor Green
Write-Host "  Shared token:                     $token" -ForegroundColor Green
$effectiveUi = if ($PSBoundParameters.ContainsKey('UiPort') -and $UiPort -gt 0) { $UiPort } else { $settings.uiPort }
if ($effectiveUi) { Write-Host "  Control page:                     http://127.0.0.1:$effectiveUi" -ForegroundColor Green }
if (-not $settings.modelEndpoint -and -not $ModelEndpoint) {
    Write-Host "  No model configured. Add modelEndpoint, modelId and apiKey to" -ForegroundColor Yellow
    Write-Host "  $settingsFile to let the host drive the phone." -ForegroundColor Yellow
}
Write-Host ""

& $launcher @hostArgs
