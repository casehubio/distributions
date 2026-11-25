# casehub-distributions

Leaf apps and packaged deployables for the [CaseHub](https://github.com/casehubio) platform. Each module is a thin assembly — wiring upstream modules into runnable applications without implementing domain logic.

## MCP Server

A standalone MCP server exposing 130+ connector tools and corpus management via stdio or HTTP transport. Ships with ref platform defaults — zero configuration needed to start.

### What's inside

| Domain | Tools | Examples |
|--------|-------|---------|
| **Calendar** | list calendars, CRUD events, sync | Book a meeting, check availability |
| **Chat** | send messages, list channels, reactions | Post to Slack, list Discord channels |
| **Email** | list mailboxes, read messages, search | Check inbox, find attachments |
| **Documents** | list, upload, download, search, share | Find a file, create a share link |
| **Contacts** | list, search, sync, CRUD | Look up a colleague, sync address book |
| **Commerce** | search products, cart, checkout, orders | Find headphones, add to cart, buy |
| **Banking** | accounts, balances, transactions, payments | Check balance, list transactions |
| **Travel** | search transport/accommodation, book | Find trains to Brighton, book a hotel |
| **Health** | search providers, book appointments, records | Find a GP, book a slot |
| **Location** | search places, directions, device position | Find nearby restaurants, get walking directions |
| **Voice** | text-to-speech, speech-to-text, voice list | Speak a message, transcribe audio |
| **Mobile** | SMS, calls, voicemail, WhatsApp | Send a text, make a call |
| **Project** | issues, labels, milestones, boards | Create an issue, move a card |
| **Corpus** | catalogue, entries, search, federation | Store knowledge, search across corpora |

### Quick start

```bash
# 1. Build the MCP server
mvn -f mcp-server/pom.xml clean package -DskipTests -Dhttp

# 2. Run it (HTTP transport for testing)
java -jar mcp-server/target/quarkus-app/quarkus-run.jar

# 3. Run the examples (requires Ollama with llama3.1:8b)
mvn -f examples/pom.xml verify
```

### Transport modes

**stdio** (default) — for MCP clients like Claude Desktop, Cursor, VS Code:
```bash
java -jar mcp-server/target/quarkus-app/quarkus-run.jar
```

**HTTP/SSE** — for LangChain4j, web clients, testing:
```bash
java -Dquarkus.http.port=8080 -jar mcp-server/target/quarkus-app/quarkus-run.jar
```

### Using with Claude Desktop

Add to your Claude Desktop MCP config (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "casehub": {
      "command": "java",
      "args": ["-jar", "/path/to/mcp-server/target/quarkus-app/quarkus-run.jar"]
    }
  }
}
```

### Using with LangChain4j

The `examples/` module contains working LangChain4j integration tests — connect to the MCP server, call tools directly or let an LLM pick the right tool.

```bash
# Direct tool tests (no LLM needed)
mvn -f examples/pom.xml verify

# With Ollama (default — llama3.1:8b)
mvn -f examples/pom.xml verify -Dllm.provider=ollama

# With Anthropic
mvn -f examples/pom.xml verify -Dllm.provider=anthropic -Dllm.model=claude-sonnet-4-20250514
```

## Examples — layered tutorial

The `examples/` module is structured as a progressive tutorial:

| Layer | What it tests | LLM needed? |
|-------|--------------|-------------|
| **Layer 1: Direct tools** | MCP server starts, tools are registered, ref data is there | No |
| **Layer 2: Corpus** | Neocortex corpus tools are registered and callable | No |
| **Layer 3: Single-domain LLM** | Model picks the right tool from 130+ options | Yes |
| **Layer 4: Multi-step LLM** | Model chains tool calls across domains | Yes |

Layer 1–2 run everywhere (CI, local, no GPU). Layer 3–4 need an LLM and are skipped when none is available.

## Modules

| Module | Description |
|--------|-------------|
| `mcp-server` | Quarkus app: connectors + neocortex corpus + platform MCP + ref defaults |
| `examples` | LangChain4j integration tests and quick-start examples |

Future: `cli` (command-line tools), `electron-app` (native launcher with bundled Ollama).

## Architecture

```
distributions/
├── mcp-server/     Quarkus app: connectors + neocortex corpus + ref defaults
├── examples/       LangChain4j quick-start and integration tests
├── cli/            (future) Command-line tools
└── electron-app/   (future) Native launcher with bundled Ollama
```

## Dependencies

This repo sits downstream of the platform stack:

- [connectors](https://github.com/casehubio/connectors) — 13 platform SPIs, 130+ MCP tools
- [neocortex](https://github.com/casehubio/neocortex) — corpus management, knowledge search
- [platform](https://github.com/casehubio/platform) — MCP infrastructure, schema generation

## Authentication

Provider authentication (OAuth2, API keys) is under active development. Currently all tools use ref platform defaults — in-memory implementations with pre-loaded test data. Real provider connections will be available via the connector lifecycle and mosaic connection UI (see [connectors#356](https://github.com/casehubio/connectors/issues/356)).
