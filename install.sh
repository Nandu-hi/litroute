#!/usr/bin/env bash
# LitRoute installer: checks (and optionally installs) the prerequisites, builds
# the project, exports the SPECTER2 model to ~/.litroute/models and creates the
# ~/.litroute folders. Safe to re-run: every step skips what is already done.
#
# Usage: ./install.sh [--yes] [--skip-model] [--skip-build] [--credentials] [--help]
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LITROUTE_HOME="$HOME/.litroute"
MODEL_DIR="${SPECTER2_OUT_DIR:-$LITROUTE_HOME/models}"
MIN_JAVA=17

ASSUME_YES=0
SKIP_MODEL=0
SKIP_BUILD=0
SETUP_CREDENTIALS=0

usage() {
    cat <<EOF
Usage: ./install.sh [options]

  --yes          install missing system packages without asking
  --skip-model   don't export the SPECTER2 model (needs ~2 GB of downloads the first time)
  --skip-build   don't run the Maven build
  --credentials  finish by setting up the Gmail App Password (needs a real terminal)
  --help         show this help

Environment:
  SPECTER2_OUT_DIR  where the model is installed (default: ~/.litroute/models)
EOF
}

for arg in "$@"; do
    case "$arg" in
        --yes|-y)      ASSUME_YES=1 ;;
        --skip-model)  SKIP_MODEL=1 ;;
        --skip-build)  SKIP_BUILD=1 ;;
        --credentials) SETUP_CREDENTIALS=1 ;;
        --help|-h)     usage; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1;34m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '    \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

confirm() {
    [ "$ASSUME_YES" -eq 1 ] && return 0
    [ -t 0 ] || return 1
    read -r -p "    $1 [y/N] " reply
    [[ "$reply" =~ ^[Yy] ]]
}

# --- package manager -------------------------------------------------------

detect_package_manager() {
    if have apt-get; then echo apt
    elif have pacman; then echo pacman
    elif have dnf; then echo dnf
    elif have zypper; then echo zypper
    elif have brew; then echo brew
    else echo none
    fi
}

PM="$(detect_package_manager)"

# The package that provides each tool, per package manager.
package_for() {
    local tool="$1"
    case "$PM:$tool" in
        apt:java)       echo openjdk-21-jdk ;;
        apt:maven)      echo maven ;;
        apt:python)     echo "python3 python3-venv python3-pip" ;;
        pacman:java)    echo jdk21-openjdk ;;
        pacman:maven)   echo maven ;;
        pacman:python)  echo python ;;
        dnf:java)       echo java-21-openjdk-devel ;;
        dnf:maven)      echo maven ;;
        dnf:python)     echo "python3 python3-pip" ;;
        zypper:java)    echo java-21-openjdk-devel ;;
        zypper:maven)   echo maven ;;
        zypper:python)  echo "python3 python3-pip" ;;
        brew:java)      echo openjdk@21 ;;
        brew:maven)     echo maven ;;
        brew:python)    echo python ;;
    esac
}

install_packages() {
    local packages="$*"
    case "$PM" in
        apt)    sudo apt-get update && sudo apt-get install -y $packages ;;
        pacman) sudo pacman -S --needed --noconfirm $packages ;;
        dnf)    sudo dnf install -y $packages ;;
        zypper) sudo zypper install -y $packages ;;
        brew)   brew install $packages ;;
        *)      return 1 ;;
    esac
}

# Installs the package for $1 if the user agrees; otherwise stops with $2 as the hint.
require() {
    local tool="$1" hint="$2" packages
    packages="$(package_for "$tool")"
    if [ -n "$packages" ] && confirm "Install $packages with $PM?"; then
        install_packages "$packages" || die "Installing $packages failed. $hint"
    else
        die "$hint"
    fi
}

java_major_version() {
    # "openjdk version "21.0.4" ..." or "java version "1.8.0_..." -> 21 or 8
    local v
    v="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
    case "$v" in
        1.*) echo "${v#1.}" | cut -d. -f1 ;;
        *)   echo "$v" | cut -d. -f1 ;;
    esac
}

# --- 1. prerequisites ------------------------------------------------------

step "Checking prerequisites (package manager: $PM)"

if ! have java || [ "$(java_major_version)" -lt "$MIN_JAVA" ]; then
    warn "Java $MIN_JAVA or newer (a JDK) is required."
    require java "Install a JDK $MIN_JAVA+ (21 recommended), e.g. from https://adoptium.net, and re-run."
fi
have javac || warn "javac not found: 'java' looks like a JRE. Maven needs a full JDK."
ok "Java $(java_major_version)"

if ! have mvn; then
    warn "Apache Maven is required."
    require maven "Install Maven 3.6+ from https://maven.apache.org/download.cgi and re-run."
fi
ok "$(mvn -v 2>/dev/null | head -1)"

if [ "$SKIP_MODEL" -eq 0 ]; then
    if ! have python3 || ! python3 -c 'import venv, ensurepip' >/dev/null 2>&1; then
        warn "Python 3 with venv is required to export the SPECTER2 model (once)."
        require python "Install Python 3.9+ with the venv module, or re-run with --skip-model."
    fi
    ok "$(python3 --version)"
fi

# --- 2. build --------------------------------------------------------------

if [ "$SKIP_BUILD" -eq 0 ]; then
    step "Building LitRoute (downloads the Maven dependencies the first time)"
    (cd "$REPO_DIR" && mvn -q -DskipTests compile >/dev/null) \
        || die "The Maven build failed. Run 'mvn -DskipTests compile' to see why."
    ok "Compiled into $REPO_DIR/target"
fi

# --- 3. SPECTER2 model -----------------------------------------------------

if [ "$SKIP_MODEL" -eq 0 ]; then
    step "Installing the SPECTER2 model in $MODEL_DIR"
    if [ -f "$MODEL_DIR/specter2.onnx" ] && [ -f "$MODEL_DIR/specter2.onnx.data" ] && [ -f "$MODEL_DIR/tokenizer.json" ]; then
        ok "Already installed; skipping (delete $MODEL_DIR to re-export)"
    else
        warn "This downloads CPU-only PyTorch and ~450 MB of model weights; it takes a few minutes."
        SPECTER2_OUT_DIR="$MODEL_DIR" bash "$REPO_DIR/scripts/model-export/setup_model.sh"
        ok "Model exported"
    fi
fi

# --- 4. data folders -------------------------------------------------------

step "Creating the LitRoute folders in $LITROUTE_HOME"
mkdir -p "$LITROUTE_HOME/profiles" "$LITROUTE_HOME/data"
ok "$LITROUTE_HOME/profiles  (one subfolder of PDFs per student)"
ok "$LITROUTE_HOME/data      (the pipeline's stores)"

if [ -z "$(find "$LITROUTE_HOME/profiles" -mindepth 1 -maxdepth 1 -type d -not -name '.*' 2>/dev/null)" ]; then
    warn "No student profiles yet. Add e.g. $LITROUTE_HOME/profiles/alice/ with their reference PDFs"
    warn "and an email.txt holding their address (see README.md, 'Student profiles')."
fi

# --- 5. Gmail credentials --------------------------------------------------

if [ "$SETUP_CREDENTIALS" -eq 1 ]; then
    step "Setting up the Gmail App Password"
    if [ -f "$LITROUTE_HOME/credentials.enc" ] && ! confirm "Credentials already exist. Replace them?"; then
        ok "Kept the existing credentials"
    else
        (cd "$REPO_DIR" && mvn -q exec:java -Dexec.mainClass=com.litroute.security.CredentialSetupCli)
    fi
elif [ ! -f "$LITROUTE_HOME/credentials.enc" ]; then
    warn "No Gmail credentials yet: set them up in the app's Gmail tab, or re-run with --credentials."
fi

# --- done ------------------------------------------------------------------

step "LitRoute is installed"
cat <<EOF
    Start the desktop app:
        cd "$REPO_DIR" && mvn -q compile javafx:run

    Or run the pipeline once from the terminal:
        mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli

    See README.md for the full guide.
EOF
