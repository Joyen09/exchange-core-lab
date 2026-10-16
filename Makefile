COMPOSE ?= docker compose
GRADLE  ?= ./gradlew

.DEFAULT_GOAL := help
.PHONY: help build test up down restart logs ps health clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

build: ## Compile and package the application
	$(GRADLE) bootJar

test: ## Run the full test suite (needs a Docker daemon for Testcontainers)
	@./scripts/docker-available.sh || { \
		echo "Docker is not reachable. Start the daemon and re-run — the integration tests"; \
		echo "cannot run without it, and would otherwise fail ~70 times with the same cause."; \
		exit 1; }
	$(GRADLE) test

testnet-check: ## Verify protocol facts against the real Binance Spot Testnet (needs your own keys)
	@test -n "$$BINANCE_TESTNET_API_KEY" || { \
		echo "BINANCE_TESTNET_API_KEY is not set, so there is nothing to check."; \
		echo ""; \
		echo "Get a key pair from the Binance Spot Testnet portal, then:"; \
		echo "    export BINANCE_TESTNET_API_KEY=..."; \
		echo "    export BINANCE_TESTNET_API_SECRET=..."; \
		echo "    make testnet-check"; \
		echo ""; \
		echo "Keys belong in a local .env. Never in this repository, never in a cloud session."; \
		exit 1; }
	@test -n "$$BINANCE_TESTNET_API_SECRET" || { \
		echo "BINANCE_TESTNET_API_KEY is set but BINANCE_TESTNET_API_SECRET is not."; exit 1; }
	$(GRADLE) test -PskipDockerCheck --tests '*BinanceTestnetProtocolIT*' --info \
		| grep -v '^\[testnet-check\] *$$' || true
	@echo ""
	@echo "Captures, if any, are in build/captured-fixtures/."
	@echo "Promoting one into src/test/resources/fakevenue/ is a deliberate commit of its own —"
	@echo "see docs/vendor/README.md for why."

up: ## Start the whole stack in the background
	$(COMPOSE) up -d --build

down: ## Stop the stack, keeping volumes
	$(COMPOSE) down

restart: down up ## Restart the stack

logs: ## Follow application logs
	$(COMPOSE) logs -f app

ps: ## Show stack status
	$(COMPOSE) ps

health: ## Query the health endpoint
	curl -fsS http://localhost:18080/health && echo

clean: ## Stop the stack and delete its volumes
	$(COMPOSE) down -v
	$(GRADLE) clean
