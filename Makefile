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
	@rm -rf build/captured-fixtures
	$(GRADLE) test -PskipDockerCheck --tests '*BinanceTestnetProtocolIT*' --info \
		| grep -v '^\[testnet-check\] *$$' || true
	@echo ""
	@test -f build/captured-fixtures/order.json || { \
		echo "======================================================================"; \
		echo "The fixture comparison did NOT run."; \
		echo ""; \
		echo "Signatures and error codes were checked, but order.json was never"; \
		echo "compared against a live response — there were no open BTCUSDT orders"; \
		echo "to compare it with. This command has not verified the fixtures."; \
		echo ""; \
		echo "Place a limit order on the testnet that will not fill (a BUY well"; \
		echo "below the bid, or a SELL well above the ask) and run this again."; \
		echo "======================================================================"; \
		exit 1; }
	@echo "Captures are in build/captured-fixtures/."
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
