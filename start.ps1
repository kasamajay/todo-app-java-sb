<#
  Brings up a todo-app Docker Compose stack (if not already running) and
  exposes it to the internet via an ngrok tunnel on the web container's port,
  then prints the public URL once ready. One tunnel covers the whole app,
  including /admin, in either mode:

    dev  (default) - docker-compose.yml, Vite dev server on 5173; Vite
                     proxies /api/* to the api container.
    prod (-Prod)   - docker-compose.prod.yml, nginx on 8081 serving the
                     prebuilt bundle; nginx proxies /api/* to the api
                     container (see decisions/0013). Rebuilt with --build on
                     each run, since prod has no hot reload.

  The two stacks share api/data, so this refuses to start one while the
  other is running. It also refuses while any stack of the sibling apps -
  todo-app (Go) or todo-app-py (Python) - is running, since all three apps
  use the same ports.

  Unlike a plain background process, the Docker stack is this project's
  normal persistent environment (see decisions/0001, the
  docker-dev-workflow skill) - so Ctrl+C here only stops the ngrok tunnel,
  not the containers. Run the printed `docker compose ... down` separately
  to stop the stack.

  Usage:  .\start.ps1          (dev)
          .\start.ps1 -Prod    (production)
  Stop:   Ctrl+C (stops the tunnel only)
#>

param(
    [switch]$Prod
)

$root = $PSScriptRoot

if ($Prod) {
    $modeLabel = "production (nginx)"
    $port = 8081
    $composeArgs = @("-f", "docker-compose.prod.yml")
} else {
    $modeLabel = "dev (Vite)"
    $port = 5173
    $composeArgs = @()
}
# The same compose args as a string, for the copy-pasteable hints printed below.
$composeHint = if ($composeArgs.Count) { "docker compose " + ($composeArgs -join " ") } else { "docker compose" }

# Stacks that must not be running alongside this one, keyed by Compose
# project name: this app's other mode (shares api/data) and every stack of
# the Go and Python versions (same host ports).
$ownProject = if ($Prod) { "todo-app-java-sb-prod" } else { "todo-app-java-sb" }
$conflicts = [ordered]@{
    "todo-app-java-sb"      = @{ label = "todo-app-java-sb dev stack";        stop = "docker compose down  (in todo-app-java-sb)" }
    "todo-app-java-sb-prod" = @{ label = "todo-app-java-sb production stack"; stop = "docker compose -f docker-compose.prod.yml down  (in todo-app-java-sb)" }
    "todo-app"              = @{ label = "todo-app (Go) dev stack";           stop = "docker compose down  (in todo-app)" }
    "todo-app-prod"         = @{ label = "todo-app (Go) production stack";    stop = "docker compose -f docker-compose.prod.yml down  (in todo-app)" }
    "todo-app-py"           = @{ label = "todo-app-py (Python) dev stack";    stop = "docker compose down  (in todo-app-py)" }
    "todo-app-py-prod"      = @{ label = "todo-app-py (Python) production stack"; stop = "docker compose -f docker-compose.prod.yml down  (in todo-app-py)" }
}
$conflicts.Remove($ownProject)

$ngrokCmd = Get-Command ngrok -ErrorAction SilentlyContinue
if (-not $ngrokCmd) {
    Write-Host "ngrok was not found on PATH." -ForegroundColor Yellow
    Write-Host "See README.md for install + authtoken setup, then re-run this script." -ForegroundColor Yellow
    exit 1
}

$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if (-not $dockerCmd) {
    Write-Host "docker was not found on PATH. Install Docker Desktop first." -ForegroundColor Yellow
    exit 1
}

Push-Location $root
try {
    # This app's dev and prod stacks both bind-mount api/data (the JSON store
    # assumes a single writer process), and todo-app's stacks bind the same
    # host ports - refuse to start while any of them is running.
    foreach ($project in $conflicts.Keys) {
        $running = docker ps -q --filter "label=com.docker.compose.project=$project"
        if ($LASTEXITCODE -eq 0 -and $running) {
            Write-Host "The $($conflicts[$project].label) is already running; it can't run alongside the $modeLabel stack." -ForegroundColor Red
            Write-Host "Stop it first with:  $($conflicts[$project].stop)" -ForegroundColor Yellow
            exit 1
        }
    }

    Write-Host "Ensuring the $modeLabel Docker Compose stack is up..." -ForegroundColor Cyan
    $upArgs = @("up", "-d")
    if ($Prod) { $upArgs += "--build" }
    docker compose @composeArgs @upArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "$composeHint up failed - see output above." -ForegroundColor Red
        exit 1
    }
}
finally {
    Pop-Location
}

Write-Host "Waiting for http://localhost:$port to respond..." -ForegroundColor Cyan
$appReady = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:$port" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        if ($resp.StatusCode -ge 200) { $appReady = $true; break }
    } catch {
        # not ready yet - keep polling
    }
    Start-Sleep -Seconds 1
}
if (-not $appReady) {
    Write-Host "Timed out waiting for the web container to respond on port $port." -ForegroundColor Yellow
    Write-Host "Check '$composeHint logs web' - continuing to start the tunnel anyway." -ForegroundColor Yellow
}

# NGROK_DOMAIN (optional, set in .env) pins the tunnel to a reserved static
# ngrok domain instead of a random one each run - needed for Google sign-in
# to work over the tunnel, since Google requires an exact, pre-registered
# redirect URI. See README's "Public access via ngrok" section.
$ngrokDomain = $null
$envPath = Join-Path $root ".env"
if (Test-Path $envPath) {
    $match = Select-String -Path $envPath -Pattern '^NGROK_DOMAIN=(.+)$'
    if ($match) { $ngrokDomain = $match.Matches[0].Groups[1].Value.Trim() }
}

if ($ngrokDomain) {
    Write-Host "Starting ngrok tunnel on static domain $ngrokDomain..." -ForegroundColor Cyan
    $ngrokArgs = @("http", "--domain=$ngrokDomain", "$port")
} else {
    Write-Host "Starting ngrok tunnel..." -ForegroundColor Cyan
    $ngrokArgs = @("http", "$port")
}
$ngrokProc = Start-Process -FilePath "ngrok" -ArgumentList $ngrokArgs -PassThru -WindowStyle Hidden

try {
    $publicUrl = $null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        try {
            $tunnels = Invoke-RestMethod -Uri "http://127.0.0.1:4040/api/tunnels" -ErrorAction Stop
            $https = $tunnels.tunnels | Where-Object { $_.proto -eq "https" } | Select-Object -First 1
            if ($https) {
                $publicUrl = $https.public_url
                break
            }
        } catch {
            # ngrok's local API isn't ready yet - keep polling
        }
    }

    Write-Host ""
    if ($publicUrl) {
        Write-Host "Mode:                 $modeLabel" -ForegroundColor Green
        Write-Host "Todo App is live at:  $publicUrl" -ForegroundColor Green
        Write-Host "Admin panel:          $publicUrl/admin" -ForegroundColor Green
        if ($Prod -and $ngrokDomain -and -not (Select-String -Path $envPath -Pattern '^PROD_FRONTEND_BASE_URL=' -Quiet)) {
            Write-Host "Google sign-in over this domain in prod needs PROD_GOOGLE_REDIRECT_URI / PROD_FRONTEND_BASE_URL in .env (see README)." -ForegroundColor DarkGray
        }
    } else {
        Write-Host "ngrok started but the public URL wasn't detected yet." -ForegroundColor Yellow
        Write-Host "Check http://127.0.0.1:4040 in a browser for the tunnel status." -ForegroundColor Yellow
    }
    Write-Host "Local copy:           http://localhost:$port"
    Write-Host ""
    Write-Host "First run? The admin@todo.io password is in: $composeHint logs api"
    Write-Host "First browser visit shows ngrok's own warning interstitial - click 'Visit Site' to continue."
    Write-Host ""
    Write-Host "Press Ctrl+C to stop the tunnel (the Docker stack keeps running)." -ForegroundColor Yellow

    while ($true) { Start-Sleep -Seconds 1 }
}
finally {
    Write-Host ""
    Write-Host "Stopping ngrok tunnel..." -ForegroundColor Cyan
    if ($ngrokProc -and -not $ngrokProc.HasExited) { Stop-Process -Id $ngrokProc.Id -Force -ErrorAction SilentlyContinue }
    Write-Host "The $modeLabel Docker Compose stack is still running - '$composeHint down' to stop it." -ForegroundColor Cyan
}
