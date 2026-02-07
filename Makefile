.PHONY: error-codes ci

error-codes:
	./scripts/generate-error-codes.sh

ci: error-codes
	git diff --exit-code docs/error-codes.md
