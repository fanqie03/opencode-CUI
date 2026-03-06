# TODO.md — Pending Items

> Captured items for future action.

- [x] Replace hardcoded AK/SK with database-backed store (REQ-26) — 已使用 credentialRepository
- [ ] Implement GatewayRelayService reconnect logic
- [ ] Implement GatewayRelayService recovery request logic
- [ ] Add automated tests for all components
- [x] Fix inconsistent DB_PASSWORD vs MYSQL_PASSWORD env var naming — 统一为 DB_PASSWORD
- [x] Change `allowed-origins: "*"` to specific origins for production — 改为 ${CORS_ORIGINS:http://localhost:5173}
- [x] Replace `changeme` internal token default — 改为 sk-intl-9f2a7d3e4b1c
- [x] Replace `com.yourapp` placeholder group ID — 改为 com.opencode.cui
