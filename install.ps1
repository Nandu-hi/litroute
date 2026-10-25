# LitRoute installer for Windows: checks (and optionally installs) the prerequisites,
# builds the project, exports the SPECTER2 model to %USERPROFILE%\.litroute\models
# and creates the .litroute folders. Safe to re-run: every step skips what is
# already done. The Windows counterpart of install.sh; works in Windows
# PowerShell 5.1 and PowerShell 7.
#
# Usage: .\install.ps1 [-Yes] [-SkipModel] [-SkipBuild] [-Credentials] [-Help]
# (or install.cmd with the same options, which bypasses the execution policy)
[CmdletBinding()]
param(
    [switch]$Yes,
    [switch]$SkipModel,
    [switch]$SkipBuild,
    [switch]$Credentials,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$RepoDir      = $PSScriptRoot
$LitRouteHome = Join-Path $env:USERPROFILE '.litroute'
$ModelDir     = if ($env:SPECTER2_OUT_DIR) { $env:SPECTER2_OUT_DIR } else { Join-Path $LitRouteHome 'models' }
$MinJava      = 17
$MavenVersion = '3.9.9'
$ExportDir    = Join-Path $RepoDir 'scripts\model-export'

if ($Help) {
    @"
Usage: .\install.ps1 [options]

  -Yes          install missing prerequisites without asking
  -SkipModel    don't export the SPECTER2 model (needs ~2 GB of downloads the first time)
  -SkipBuild    don't run the Maven build
  -Credentials  finish by setting up the Gmail App Password
  -Help         show this help

Environment:
  SPECTER2_OUT_DIR  where the model is installed (default: %USERPROFILE%\.litroute\models)
"@
    exit 0
}

function Step($message) { Write-Host ''; Write-Host "==> $message" -ForegroundColor Cyan }
function Ok($message)   { Write-Host "    [ok] $message" -ForegroundColor Green }
function Warn($message) { Write-Host "    [!] $message" -ForegroundColor Yellow }
function Die($message)  { Write-Host "error: $message" -ForegroundColor Red; exit 1 }
function Have($command) { [bool](Get-Command $command -ErrorAction SilentlyContinue) }

function Confirm-Step($question) {
    if ($Yes) { return $true }
    $reply = Read-Host "    $question [y/N]"
    return $reply -match '^[Yy]'
}

# Runs a native command and returns its stdout and stderr as one string, without
# PowerShell 5.1 turning the stderr lines into errors (java -version writes there).
function Invoke-Quiet([string]$command, [string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $command @arguments 2>&1 | ForEach-Object { "$_" }
        return @{ Output = ($output -join "`n"); ExitCode = $LASTEXITCODE }
    } catch {
        return @{ Output = ''; ExitCode = 1 }
    } finally {
        $ErrorActionPreference = $previous
    }
}

# Picks up PATH changes made by installers since this window opened.
function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user    = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
}

function Install-WithWinget($id, $hint) {
    if (-not (Have 'winget')) { Die "$hint (winget is not available to install it automatically.)" }
    if (-not (Confirm-Step "Install $id with winget?")) { Die $hint }
    & winget install --id $id -e --accept-source-agreements --accept-package-agreements
    if ($LASTEXITCODE -ne 0) { Die "Installing $id failed. $hint" }
    Update-SessionPath
}

function Get-JavaMajorVersion {
    if (-not (Have 'java')) { return 0 }
    $result = Invoke-Quiet 'java' @('-version')
    if ($result.Output -match 'version "(\d+)(?:\.(\d+))?') {
        if ($Matches[1] -eq '1') { return [int]$Matches[2] }   # "1.8.0_..." -> 8
        return [int]$Matches[1]
    }
    return 0
}

# Returns the command (plus leading arguments) that runs a Python 3.9+ with venv, or $null.
# Skips the Microsoft Store "python" alias, which prints nothing and fails.
function Find-Python {
    $check = 'import sys, venv, ensurepip; sys.exit(0 if sys.version_info >= (3, 9) else 1)'
    foreach ($candidate in @(@('py', '-3'), @('python'), @('python3'))) {
        if (-not (Have $candidate[0])) { continue }
        $arguments = @($candidate | Select-Object -Skip 1) + @('-c', $check)
        if ((Invoke-Quiet $candidate[0] $arguments).ExitCode -eq 0) { return ,$candidate }
    }
    return $null
}

function Install-Maven {
    $hint = 'Install Maven 3.6+ from https://maven.apache.org/download.cgi, add its bin folder to PATH and re-run.'
    if (-not (Confirm-Step "Download Maven $MavenVersion into $env:LOCALAPPDATA\Programs?")) { Die $hint }
    $programs = Join-Path $env:LOCALAPPDATA 'Programs'
    $zip = Join-Path $env:TEMP "apache-maven-$MavenVersion-bin.zip"
    $url = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
    New-Item -ItemType Directory -Force -Path $programs | Out-Null
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $programs -Force
    Remove-Item $zip
    $bin = Join-Path $programs "apache-maven-$MavenVersion\bin"
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (($userPath -split ';') -notcontains $bin) {
        [Environment]::SetEnvironmentVariable('Path', "$userPath;$bin".TrimStart(';'), 'User')
    }
    Update-SessionPath
    Ok "Maven installed in $bin (added to your PATH; new windows pick it up)"
}

# --- 1. prerequisites ------------------------------------------------------

Step 'Checking prerequisites'

if ((Get-JavaMajorVersion) -lt $MinJava) {
    Warn "Java $MinJava or newer (a JDK) is required."
    Install-WithWinget 'EclipseAdoptium.Temurin.21.JDK' "Install a JDK $MinJava+ (21 recommended) from https://adoptium.net and re-run."
    if ((Get-JavaMajorVersion) -lt $MinJava) { Die 'Java was installed but is not on PATH yet. Open a new window and re-run.' }
}
if (-not (Have 'javac')) { Warn "javac not found: 'java' looks like a JRE. Maven needs a full JDK." }
Ok "Java $(Get-JavaMajorVersion)"

if (-not (Have 'mvn')) {
    Warn 'Apache Maven is required.'
    Install-Maven
}
Ok ((Invoke-Quiet 'mvn' @('-v')).Output -split "`n" | Select-Object -First 1)

$Python = $null
if (-not $SkipModel) {
    $Python = Find-Python
    if (-not $Python) {
        Warn 'Python 3 is required to export the SPECTER2 model (once).'
        Install-WithWinget 'Python.Python.3.12' 'Install Python 3.9+ from https://www.python.org/downloads/windows/, or re-run with -SkipModel.'
        $Python = Find-Python
        if (-not $Python) { Die 'Python was installed but is not on PATH yet. Open a new window and re-run.' }
    }
    $pythonArgs = @($Python | Select-Object -Skip 1) + @('--version')
    $pythonVersion = (Invoke-Quiet $Python[0] $pythonArgs).Output
    Ok $pythonVersion
}

# --- 2. build --------------------------------------------------------------

if (-not $SkipBuild) {
    Step 'Building LitRoute (downloads the Maven dependencies the first time)'
    Push-Location $RepoDir
    try {
        & mvn -q -DskipTests compile
        if ($LASTEXITCODE -ne 0) { Die "The Maven build failed. Run 'mvn -DskipTests compile' to see why." }
    } finally { Pop-Location }
    Ok "Compiled into $RepoDir\target"
}

# --- 3. SPECTER2 model -----------------------------------------------------

if (-not $SkipModel) {
    Step "Installing the SPECTER2 model in $ModelDir"
    $installed = (Test-Path (Join-Path $ModelDir 'specter2.onnx')) -and
                 (Test-Path (Join-Path $ModelDir 'specter2.onnx.data')) -and
                 (Test-Path (Join-Path $ModelDir 'tokenizer.json'))
    if ($installed) {
        Ok "Already installed; skipping (delete $ModelDir to re-export)"
    } else {
        Warn 'This downloads CPU-only PyTorch and ~450 MB of model weights; it takes a few minutes.'
        # The same steps as scripts/model-export/setup_model.sh.
        $venv = Join-Path $ExportDir '.venv'
        $venvPython = Join-Path $venv 'Scripts\python.exe'
        if (-not (Test-Path $venvPython)) {
            Write-Host "    Creating venv at $venv ..."
            $venvArgs = @($Python | Select-Object -Skip 1) + @('-m', 'venv', $venv)
            & $Python[0] @venvArgs
            if ($LASTEXITCODE -ne 0) { Die 'Creating the Python venv failed.' }
        }
        $env:SPECTER2_OUT_DIR = $ModelDir
        Write-Host '    Installing dependencies (CPU-only PyTorch + transformers/adapters/onnx) ...'
        & $venvPython -m pip install --quiet --upgrade pip
        & $venvPython -m pip install --quiet torch --index-url https://download.pytorch.org/whl/cpu
        if ($LASTEXITCODE -ne 0) { Die 'Installing PyTorch failed. If your Python is very new, install Python 3.12 and re-run.' }
        & $venvPython -m pip install --quiet -r (Join-Path $ExportDir 'requirements.txt')
        if ($LASTEXITCODE -ne 0) { Die 'Installing the export dependencies failed.' }
        Write-Host '    Exporting SPECTER2 to ONNX ...'
        & $venvPython (Join-Path $ExportDir 'export_specter2.py')
        if ($LASTEXITCODE -ne 0) { Die 'The SPECTER2 export failed.' }
        Write-Host '    Verifying the export ...'
        & $venvPython (Join-Path $ExportDir 'verify_onnx.py')
        if ($LASTEXITCODE -ne 0) { Die 'The exported model failed verification.' }
        Ok 'Model exported'
    }
}

# --- 4. data folders -------------------------------------------------------

Step "Creating the LitRoute folders in $LitRouteHome"
$profilesDir = Join-Path $LitRouteHome 'profiles'
New-Item -ItemType Directory -Force -Path $profilesDir, (Join-Path $LitRouteHome 'data') | Out-Null
Ok "$profilesDir  (one subfolder of PDFs per student)"
Ok "$(Join-Path $LitRouteHome 'data')      (the pipeline's stores)"

$students = Get-ChildItem -Path $profilesDir -Directory | Where-Object { -not $_.Name.StartsWith('.') }
if (-not $students) {
    Warn "No student profiles yet. Add e.g. $profilesDir\alice\ with their reference PDFs"
    Warn "and an email.txt holding their address (see README.md, 'Student profiles')."
}

# --- 5. Gmail credentials --------------------------------------------------

$credentialFile = Join-Path $LitRouteHome 'credentials.enc'
if ($Credentials) {
    Step 'Setting up the Gmail App Password'
    if ((Test-Path $credentialFile) -and -not (Confirm-Step 'Credentials already exist. Replace them?')) {
        Ok 'Kept the existing credentials'
    } else {
        Push-Location $RepoDir
        try { & mvn -q exec:java '-Dexec.mainClass=com.litroute.security.CredentialSetupCli' } finally { Pop-Location }
    }
} elseif (-not (Test-Path $credentialFile)) {
    Warn "No Gmail credentials yet: set them up in the app's Gmail tab, or re-run with -Credentials."
}

# --- done ------------------------------------------------------------------

Step 'LitRoute is installed'
@"
    Start the desktop app:
        cd "$RepoDir"; mvn -q compile javafx:run

    Or run the pipeline once from the terminal:
        mvn -q compile exec:java "-Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli"

    See README.md for the full guide.
"@ | Write-Host
