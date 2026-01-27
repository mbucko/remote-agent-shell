# RemoteAgentShell Makefile
# Run 'make help' for available commands

.PHONY: help install install-dev test test-daemon test-pairing test-crypto \
        test-cov lint format proto clean build

# Default target
help:
	@echo "RemoteAgentShell Development Commands"
	@echo ""
	@echo "Setup:"
	@echo "  make install        Install daemon dependencies"
	@echo "  make install-dev    Install daemon with dev dependencies"
	@echo ""
	@echo "Testing:"
	@echo "  make test           Run all daemon tests"
	@echo "  make test-quick     Run tests without slow integration tests"
	@echo "  make test-pairing   Run pairing module tests only"
	@echo "  make test-crypto    Run crypto tests only"
	@echo "  make test-cov       Run tests with coverage report"
	@echo "  make test-verbose   Run tests with verbose output"
	@echo ""
	@echo "Code Quality:"
	@echo "  make lint           Run linter (ruff)"
	@echo "  make format         Format code (ruff)"
	@echo "  make typecheck      Run type checker (mypy)"
	@echo ""
	@echo "Proto:"
	@echo "  make proto          Generate Python code from proto files"
	@echo "  make proto-clean    Remove generated proto files"
	@echo ""
	@echo "Build:"
	@echo "  make build          Build daemon package"
	@echo "  make clean          Remove build artifacts"
	@echo ""
	@echo "Development:"
	@echo "  make run-pair       Run pairing command (for testing)"
	@echo "  make shell          Open Python shell with ras module"

# =============================================================================
# Setup
# =============================================================================

install:
	cd daemon && pip install -e .

install-dev:
	cd daemon && pip install -e ".[dev]"

# =============================================================================
# Testing
# =============================================================================

test:
	cd daemon && python -m pytest tests/ -q

test-quick:
	cd daemon && python -m pytest tests/ -q -m "not integration"

test-verbose:
	cd daemon && python -m pytest tests/ -v

test-pairing:
	cd daemon && python -m pytest tests/pairing/ -v

test-crypto:
	cd daemon && python -m pytest tests/crypto/ -v

test-e2e:
	cd daemon && python -m pytest tests/pairing/test_e2e_pairing.py -v

test-vectors:
	cd daemon && python -m pytest tests/pairing/test_vectors.py -v

test-cov:
	cd daemon && python -m pytest tests/ --cov=src/ras --cov-report=term-missing --cov-report=html

test-cov-pairing:
	cd daemon && python -m pytest tests/pairing/ --cov=src/ras/pairing --cov-report=term-missing

# =============================================================================
# Code Quality
# =============================================================================

lint:
	cd daemon && ruff check src/ tests/

format:
	cd daemon && ruff format src/ tests/

format-check:
	cd daemon && ruff format --check src/ tests/

typecheck:
	cd daemon && mypy src/ras --ignore-missing-imports

# =============================================================================
# Proto Generation
# =============================================================================

PROTO_DIR := proto
PROTO_OUT := daemon/src/ras/proto/ras

proto:
	@echo "Generating Python code from proto files..."
	@mkdir -p $(PROTO_OUT)
	cd daemon && python -m grpc_tools.protoc \
		-I../$(PROTO_DIR) \
		--python_betterproto_out=src/ras/proto/ras \
		../$(PROTO_DIR)/*.proto
	@echo "Proto files generated in $(PROTO_OUT)"

proto-clean:
	rm -rf $(PROTO_OUT)/__init__.py
	@echo "Proto files cleaned"

# =============================================================================
# Build
# =============================================================================

build:
	cd daemon && python -m build

clean:
	rm -rf daemon/dist daemon/build daemon/*.egg-info
	rm -rf daemon/.pytest_cache daemon/.coverage daemon/htmlcov
	find daemon -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find daemon -type f -name "*.pyc" -delete
	@echo "Build artifacts cleaned"

# =============================================================================
# Development
# =============================================================================

run-pair:
	cd daemon && PYTHONPATH=src python -c "from ras.cli import main; main()" pair --help

shell:
	cd daemon && PYTHONPATH=src python -i -c "from ras import *; print('ras module loaded')"

# =============================================================================
# Docker (placeholder for future)
# =============================================================================

docker-build:
	@echo "Docker build not yet implemented"

docker-run:
	@echo "Docker run not yet implemented"

# =============================================================================
# CI/CD helpers
# =============================================================================

ci-test:
	cd daemon && python -m pytest tests/ -q --tb=short

ci-lint:
	cd daemon && ruff check src/ tests/ --output-format=github

check-all: lint typecheck test
	@echo "All checks passed!"
