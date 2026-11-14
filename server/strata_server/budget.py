"""Keep the existing paid execution ceiling; reserve time for durable saves."""
WORKER_SECONDS=7200
SAVE_RESERVE_SECONDS=300
SOFT_SECONDS=WORKER_SECONDS-SAVE_RESERVE_SECONDS

class BudgetExpired(TimeoutError):pass
