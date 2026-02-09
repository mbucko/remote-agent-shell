# RemoteAgentShell Makefile
# Run 'make' or 'make help' for available commands

.DEFAULT_GOAL := help

DAEMON := cd daemon &&
DAEMON_RUN := $(DAEMON) uv run

.PHONY: help check-deps install install-dev dev setup-hooks \
        test test-quick test-performance test-verbose test-pairing test-crypto \
        test-e2e test-vectors test-cov test-cov-pairing \
        lint format format-check typecheck check-all \
        proto proto-clean build clean \
        android-build android-install android-deploy android-test android-test-unit \
        android-test-integration android-test-e2e android-clean \
        daemon-restart \
        server-deploy server-pull server-restart \
        ci-test ci-lint run-pair shell

# =============================================================================
# Help
# =============================================================================

help: ## Show available commands
	@echo "RemoteAgentShell Development Commands"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'

# =============================================================================
# Setup
# =============================================================================

check-deps: ## Verify required tools are installed
	@command -v uv >/dev/null 2>&1 || { echo "uv is required. Install: https://docs.astral.sh/uv/"; exit 1; }
	@echo "All dependencies found"

install: ## Install daemon dependencies
	$(DAEMON) uv sync --no-dev

install-dev: ## Install daemon with dev dependencies
	$(DAEMON) uv sync

dev: install-dev check-deps ## Set up development environment
	@echo "Ready to develop! Run 'make test' to verify."

setup-hooks: ## Install git pre-commit hooks
	@printf '#!/bin/sh\nmake check-all\n' > .git/hooks/pre-commit
	@chmod +x .git/hooks/pre-commit
	@echo "Pre-commit hook installed"

# =============================================================================
# Testing
# =============================================================================

test: ## Run all daemon tests
	$(DAEMON_RUN) pytest tests/ -q

test-quick: ## Run tests without slow integration tests
	$(DAEMON_RUN) pytest tests/ -q -m "not integration and not performance"

test-performance: ## Run performance tests
	$(DAEMON_RUN) pytest tests/performance/ -v

test-verbose: ## Run tests with verbose output
	$(DAEMON_RUN) pytest tests/ -v

test-pairing: ## Run pairing module tests only
	$(DAEMON_RUN) pytest tests/pairing/ -v

test-crypto: ## Run crypto tests only
	$(DAEMON_RUN) pytest tests/crypto/ -v

test-e2e: ## Run end-to-end pairing tests
	$(DAEMON_RUN) pytest tests/pairing/test_e2e_pairing.py -v

test-vectors: ## Run test vectors
	$(DAEMON_RUN) pytest tests/pairing/test_vectors.py -v

test-cov: ## Run tests with coverage report
	$(DAEMON_RUN) pytest tests/ --cov=src/ras --cov-report=term-missing --cov-report=html

test-cov-pairing: ## Run pairing tests with coverage
	$(DAEMON_RUN) pytest tests/pairing/ --cov=src/ras/pairing --cov-report=term-missing

# =============================================================================
# Code Quality
# =============================================================================

lint: ## Run linter (ruff)
	$(DAEMON_RUN) ruff check src/ tests/

format: ## Format code (ruff)
	$(DAEMON_RUN) ruff format src/ tests/

format-check: ## Check code formatting
	$(DAEMON_RUN) ruff format --check src/ tests/

typecheck: ## Run type checker (mypy)
	$(DAEMON_RUN) mypy src/ras --ignore-missing-imports

check-all: lint typecheck test ## Run all checks (lint + typecheck + test)
	@echo "All checks passed!"

# =============================================================================
# Proto Generation
# =============================================================================

PROTO_DIR := proto
PROTO_OUT := daemon/src/ras/proto/ras

# NOTE: Proto generation currently has issues with betterproto enum naming.
# The committed proto files use short enum names (ACTIVE, not SESSION_STATUS_ACTIVE).
# Regenerating will break existing code. Only regenerate if you plan to update all enum usages.
proto: ## Generate Python code from proto files
	@echo "Generating Python code from proto files..."
	@mkdir -p $(PROTO_OUT)
	$(DAEMON_RUN) python -m grpc_tools.protoc \
		-I../$(PROTO_DIR) \
		--python_betterproto_out=src/ras/proto/ras \
		../$(PROTO_DIR)/*.proto
	@echo "Proto files generated in $(PROTO_OUT)"
	@echo ""
	@echo "WARNING: You may need to update ras/proto/ras/ras/__init__.py to re-export new types"
	@echo "         if you added new .proto files."

proto-clean: ## Remove generated proto files
	rm -rf $(PROTO_OUT)/__init__.py $(PROTO_OUT)/ras $(PROTO_OUT)/clipboard
	@echo "Proto files cleaned"

# =============================================================================
# Build
# =============================================================================

build: ## Build daemon package
	$(DAEMON_RUN) python -m build

clean: ## Remove build artifacts
	rm -rf daemon/dist daemon/build daemon/*.egg-info
	rm -rf daemon/.pytest_cache daemon/.coverage daemon/htmlcov
	find daemon -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find daemon -type f -name "*.pyc" -delete
	@echo "Build artifacts cleaned"

# =============================================================================
# Development
# =============================================================================

run-pair: ## Run pairing command (for testing)
	$(DAEMON_RUN) ras pair --help

shell: ## Open Python shell with ras module
	$(DAEMON_RUN) python -i -c "from ras import *; print('ras module loaded')"

# =============================================================================
# Android
# =============================================================================

android-build: ## Build Android debug APK
	@cd android && ./gradlew assembleDebug

android-install: ## Install debug APK on connected device
	@cd android && ./gradlew installDebug

android-deploy: android-build android-install daemon-restart ## Build, install, and restart daemon
	@echo "Android deployed and daemon restarted"

android-test: ## Run all Android tests
	@cd android && ./gradlew testDebugUnitTest --rerun-tasks

android-test-unit: ## Run Android unit tests only
	@cd android && ./gradlew testDebugUnitTest --rerun-tasks -Dtags=unit

android-test-integration: ## Run Android integration tests
	@cd android && ./gradlew testDebugUnitTest --rerun-tasks -Dtags=integration

android-test-e2e: ## Run Android E2E tests
	@cd android && ./gradlew testDebugUnitTest --rerun-tasks -Dtags=e2e

android-clean: ## Clean Android build artifacts
	@cd android && ./gradlew clean

# =============================================================================
# Daemon
# =============================================================================

LOG_DIR := /tmp/ras-logs
LOG_FILE := $(LOG_DIR)/daemon.log

daemon-restart: ## Restart daemon with logs at /tmp/ras-logs/daemon.log
	@echo "Stopping daemon (force kill)..."
	-pkill -f "ras daemon start" 2>/dev/null || true
	-sleep 2
	@mkdir -p $(LOG_DIR)
	@echo "Starting daemon with logs at $(LOG_FILE)..."
	cd daemon && nohup uv run ras daemon start > $(LOG_FILE) 2>&1 &
	@sleep 3
	@ps aux | grep "ras daemon start" | grep -v grep || echo "Daemon PID not found"
	@echo "Daemon started. Logs: tail -f $(LOG_FILE)"

# =============================================================================
# Server (remote)
# =============================================================================

REMOTE_REPO := ~/repos/remote-agent-shell

server-deploy: server-pull server-restart ## Pull latest code and restart daemon on server
	@echo "Server deploy complete"

server-pull: ## Pull latest code on server
	@echo "Pulling latest code on server..."
	ssh server "cd $(REMOTE_REPO) && git pull"

server-restart: ## Restart daemon on server
	@echo "Stopping daemon on server..."
	-ssh server "pkill -f 'ras daemon [s]tart'" 2>/dev/null; true
	@sleep 2
	@echo "Starting daemon on server..."
	ssh server "cd $(REMOTE_REPO) && nohup /root/.local/bin/uv run --project daemon ras daemon start > /tmp/ras-daemon.log 2>&1 < /dev/null &"
	@echo "Daemon restarted. Logs: ssh server 'tail -f /tmp/ras-daemon.log'"

# =============================================================================
# CI/CD
# =============================================================================

ci-test: ## Run tests for CI
	$(DAEMON_RUN) pytest tests/ -q --tb=short

ci-lint: ## Run linter for CI (GitHub format)
	$(DAEMON_RUN) ruff check src/ tests/ --output-format=github
