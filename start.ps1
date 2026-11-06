param(
    [ValidateSet('All', 'Backend', 'Frontend')]
    [string]$Service = 'All',
    [ValidateSet('dev', 'compute')]
    [string]$Profile = 'dev',
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

try {
    if ($Service -eq 'Backend') {
        Set-Location -LiteralPath (Join-Path $PSScriptRoot 'bemodel-server')
        $env:SPRING_PROFILES_ACTIVE = $Profile
        & mvn spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw 'Backend exited with an error. See the log above.' }
        exit 0
    }

    if ($Service -eq 'Frontend') {
        Set-Location -LiteralPath (Join-Path $PSScriptRoot 'bemodel-web')
        if (Get-Command pnpm -ErrorAction SilentlyContinue) {
            & pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { throw 'pnpm install failed.' }
            & pnpm dev --strictPort
        } else {
            & npm install --no-package-lock
            if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
            & npm run dev -- --strictPort
        }
        if ($LASTEXITCODE -ne 0) { throw 'Frontend exited with an error. See the log above.' }
        exit 0
    }

    foreach ($tool in @('java', 'mvn', 'node')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "Missing $tool. Install it and add it to PATH, then reopen this script."
        }
    }
    if (-not (Get-Command pnpm -ErrorAction SilentlyContinue) -and
        -not (Get-Command npm -ErrorAction SilentlyContinue)) {
        throw 'Install pnpm or npm to start the frontend.'
    }

    # Capture stderr without treating Java version output as a PowerShell error.
    $javaInfo = & cmd.exe /d /c 'java -version 2>&1'
    if ($LASTEXITCODE -ne 0 -or ($javaInfo -join ' ') -notmatch 'version "(\d+)') {
        throw 'Unable to detect Java version.'
    }
    if ([int]$Matches[1] -lt 21) { throw 'JDK 21 or newer is required.' }
    $nodeInfo = & node --version
    if ($LASTEXITCODE -ne 0 -or $nodeInfo -notmatch '^v(\d+)' -or [int]$Matches[1] -lt 18) {
        throw 'Node.js 18 or newer is required.'
    }
    & mvn --version
    if ($LASTEXITCODE -ne 0) { throw 'Maven is unavailable. Check JAVA_HOME and PATH.' }

    $configPath = Join-Path $PSScriptRoot "bemodel-server/src/main/resources/application-$Profile.yml"
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw "Missing application-$Profile.yml."
    }

    if ($CheckOnly) {
        Write-Host 'Local prerequisites and configuration checked. No services started.'
        exit 0
    }

    foreach ($port in @(18080, 5173)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            throw "Port $port is already in use. Stop that service before starting again."
        }
    }

    # Pass the selected profile to the backend window.
    $scriptPath = '"' + (Join-Path $PSScriptRoot 'start.ps1') + '"'
    foreach ($part in @('Backend', 'Frontend')) {
        Start-Process -FilePath 'powershell.exe' -WindowStyle Normal -WorkingDirectory $PSScriptRoot `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath, '-Service', $part, '-Profile', $Profile)
    }
    Write-Host 'Backend and frontend are starting in separate windows.'
    Write-Host 'Open http://127.0.0.1:5173 after both services finish starting.'
    Write-Host 'First startup downloads dependencies and initializes the database; allow several minutes.'
    Write-Host 'Stop each service with Ctrl+C in its window.'
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    if ($Service -ne 'All') { Read-Host 'Press Enter to close' | Out-Null }
    exit 1
}
