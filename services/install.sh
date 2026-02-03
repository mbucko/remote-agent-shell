#!/bin/bash
#
# Install RemoteAgentShell daemon as a system service
#
# Usage:
#   ./install.sh          # Auto-detect OS and install
#   ./install.sh --remove # Remove the service
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
DAEMON_DIR="$REPO_DIR/daemon"

# Detect OS
if [[ "$OSTYPE" == "darwin"* ]]; then
    OS="macos"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS="linux"
else
    echo "Unsupported OS: $OSTYPE"
    exit 1
fi

# Find uv in PATH or common locations
find_uv() {
    # Check if already in PATH
    if command -v uv &> /dev/null; then
        echo "$(command -v uv)"
        return 0
    fi

    # Check common installation locations
    COMMON_PATHS=(
        "$HOME/.local/bin/uv"
        "$HOME/.cargo/bin/uv"
        "/usr/local/bin/uv"
        "/opt/uv/uv"
    )

    for path in "${COMMON_PATHS[@]}"; do
        if [ -x "$path" ]; then
            echo "$path"
            return 0
        fi
    done

    return 1
}

UV_PATH=$(find_uv)

if [ -z "$UV_PATH" ]; then
    echo "Error: uv is not installed."
    echo "Install it from: https://docs.astral.sh/uv/"
    echo ""
    echo "Quick install:"
    echo "  curl -LsSf https://astral.sh/uv/install.sh | sh"
    echo ""
    echo "Then either:"
    echo "  1. Open a new terminal"
    echo "  2. Run: export PATH=\"\$HOME/.local/bin:\$PATH\""
    exit 1
fi

# Add uv's directory to PATH for this session
export PATH="$(dirname "$UV_PATH"):$PATH"

install_macos() {
    echo "Installing RemoteAgentShell daemon for macOS..."

    PLIST_SRC="$SCRIPT_DIR/com.ras.daemon.plist"
    PLIST_DST="$HOME/Library/LaunchAgents/com.ras.daemon.plist"

    # Create LaunchAgents directory if it doesn't exist
    mkdir -p "$HOME/Library/LaunchAgents"

    # Generate plist with correct paths
    cat > "$PLIST_DST" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.ras.daemon</string>

    <key>ProgramArguments</key>
    <array>
        <string>$UV_PATH</string>
        <string>run</string>
        <string>--project</string>
        <string>$DAEMON_DIR</string>
        <string>ras</string>
        <string>daemon</string>
        <string>start</string>
    </array>

    <key>RunAtLoad</key>
    <true/>

    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>

    <key>ThrottleInterval</key>
    <integer>5</integer>

    <key>WorkingDirectory</key>
    <string>$HOME</string>

    <key>StandardOutPath</key>
    <string>/tmp/ras.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/ras.log</string>

    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$HOME/.local/bin</string>
        <key>HOME</key>
        <string>$HOME</string>
    </dict>
</dict>
</plist>
EOF

    # Load the service
    launchctl unload "$PLIST_DST" 2>/dev/null || true
    launchctl load "$PLIST_DST"

    echo "Installed! The daemon will start automatically on login."
    echo ""
    echo "Commands:"
    echo "  Start:   launchctl start com.ras.daemon"
    echo "  Stop:    launchctl stop com.ras.daemon"
    echo "  Status:  launchctl list | grep ras"
    echo "  Logs:    tail -f /tmp/ras.log"
    echo "  Remove:  $0 --remove"
}

install_linux() {
    echo "Installing RemoteAgentShell daemon for Linux..."

    SERVICE_DIR="$HOME/.config/systemd/user"
    SERVICE_DST="$SERVICE_DIR/ras.service"

    # Create systemd user directory if it doesn't exist
    mkdir -p "$SERVICE_DIR"

    # Generate service file with correct paths
    cat > "$SERVICE_DST" << EOF
[Unit]
Description=RemoteAgentShell Daemon
Documentation=https://github.com/mbucko/remote-agent-shell
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$UV_PATH run --project $DAEMON_DIR ras daemon start
Restart=on-failure
RestartSec=5
Environment="HOME=$HOME"
Environment="PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$HOME/.local/bin:$HOME/.cargo/bin"
StandardOutput=journal
StandardError=journal
SyslogIdentifier=ras
NoNewPrivileges=yes
PrivateTmp=yes

[Install]
WantedBy=default.target
EOF

    # Reload and enable the service
    systemctl --user daemon-reload
    systemctl --user enable ras
    systemctl --user start ras

    echo "Installed! The daemon will start automatically on login."
    echo ""
    echo "Commands:"
    echo "  Start:   systemctl --user start ras"
    echo "  Stop:    systemctl --user stop ras"
    echo "  Status:  systemctl --user status ras"
    echo "  Logs:    journalctl --user -u ras -f"
    echo "  Remove:  $0 --remove"
}

remove_macos() {
    echo "Removing RemoteAgentShell daemon from macOS..."

    PLIST_DST="$HOME/Library/LaunchAgents/com.ras.daemon.plist"

    launchctl unload "$PLIST_DST" 2>/dev/null || true
    rm -f "$PLIST_DST"

    echo "Removed!"
}

remove_linux() {
    echo "Removing RemoteAgentShell daemon from Linux..."

    SERVICE_DST="$HOME/.config/systemd/user/ras.service"

    systemctl --user stop ras 2>/dev/null || true
    systemctl --user disable ras 2>/dev/null || true
    rm -f "$SERVICE_DST"
    systemctl --user daemon-reload

    echo "Removed!"
}

# Main
if [[ "$1" == "--remove" ]]; then
    if [[ "$OS" == "macos" ]]; then
        remove_macos
    else
        remove_linux
    fi
else
    # Install dependencies first
    echo "Installing dependencies..."
    (cd "$DAEMON_DIR" && uv sync)
    echo ""

    if [[ "$OS" == "macos" ]]; then
        install_macos
    else
        install_linux
    fi
fi
