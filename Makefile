COMPOSE ?= docker compose
GRADLE  ?= ./gradlew

.DEFAULT_GOAL := help
.PHONY: help build test up down restart logs ps health clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

build: ## Compile and package the application
	$(GRADLE) bootJar

test: ## Run the full test suite (needs a Docker daemon for Testcontainers)
	$(GRADLE) test

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
