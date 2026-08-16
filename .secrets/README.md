# Local deployment secrets

This directory contains deploy-time secrets mounted into containers as read-only files.

1. Run `scripts/migrate-secrets.ps1` to migrate an existing `.env`, or copy each required `*.example` file to the same name without `.example`.
2. Never commit files without the `.example` suffix.
3. Keep one secret per file with no variable name or quotes.
4. Restrict real files to the deployment account. On Linux use `chmod 600 .secrets/*`.

Required for application startup:

- `jwt_secret`: stable JWT signing secret with at least 32 bytes.
- `service_jwt_active_secret`: independent service-to-service HS256 secret with at least 32 random bytes; do not reuse the user JWT secret.

During rotation, mount the previous service secret through a deployment override and set the same absolute previous-valid-until epoch on all services. Remove it after the bounded overlap; see Workflow `docs/SERVICE_JWT.md`.

Optional platform AI secrets:

- `llm_api_key`: primary chat provider, such as DeepSeek.
- `ark_api_key`: Ark/OpenAI-compatible fallback used by generation and VLM.
- `rag_query_api_key`: optional query-generation override; an empty file falls back to `ark_api_key`.
- `vlm_api_key`: optional VLM override; an empty file falls back to `ark_api_key`.
- `rabbitmq_password`: RabbitMQ application user password; use a distinct long random value.

Account passwords are not stored here. They are accepted only over HTTPS and stored as BCrypt hashes. Future user-owned provider keys must use encrypted backend storage, not these global platform secret files.
