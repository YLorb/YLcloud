# Database initialization

Flyway is the only database schema owner. Runtime migrations live in `db/migration` and are applied in version order.

The SQL files directly under `db/` are retained as legacy references only. Do not mount this directory into MySQL's `docker-entrypoint-initdb.d`, and do not run those files for a new installation.
