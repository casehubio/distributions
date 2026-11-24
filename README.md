# casehub-distributions

Leaf apps and packaged deployables for the [CaseHub](https://github.com/casehubio) platform.

## Modules

| Module | Description |
|--------|-------------|
| `mcp-server` | Standalone MCP server exposing 130+ connector tools via stdio or HTTP transport |

## Quick Start

```bash
# Build
mvn clean install

# Run MCP server (stdio transport, default)
java -jar mcp-server/target/quarkus-app/quarkus-run.jar

# Run MCP server (HTTP transport)
java -Dhttp -jar mcp-server/target/quarkus-app/quarkus-run.jar
```

## Architecture

Each module is a thin assembly — it wires upstream platform modules into a runnable application without implementing domain logic.

```
distributions/
├── mcp-server/     Quarkus app: connectors + platform MCP + ref defaults
├── cli/            (future) Command-line tools
└── electron-app/   (future) Native launcher with bundled Ollama
```

## Dependencies

This repo sits downstream of the platform stack:

- [connectors](https://github.com/casehubio/connectors) — connector tools and ref implementations
- [platform](https://github.com/casehubio/platform) — MCP infrastructure
