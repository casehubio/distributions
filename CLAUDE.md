# CLAUDE.md
**Name:** casehub-distributions

## Project Type

type: java
**Stage:** pre-release

## Work Tracking

Issue tracking: enabled
GitHub repo: casehubio/distributions
Issue repo: casehubio/connectors

## What This Project Is

Leaf apps and packaged deployables for the casehubio platform. Each module is a thin assembly that wires together upstream platform modules (connectors, platform, mosaic) into a runnable application. This repo sits downstream of everything and owns packaging, distribution, and first-run experience.

### Modules

| Module | Purpose |
|--------|---------|
| `mcp-server` | Standalone MCP server exposing connectors' 130+ tools with ref platform defaults |

Future modules: `cli` (command-line tools), `electron-app` (native launcher with Ollama).

## Key Rule

This is an assembly repo. Modules here wire upstream dependencies — they do not implement domain logic, SPIs, or platform infrastructure. If you're writing more than CDI wiring and configuration, the code belongs upstream.

## Build and Test

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

## Java on This Machine

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26)    # Java 26, use for dev and tests
```

## Ecosystem Conventions

**Quarkus version:** All projects use `3.32.2`. When bumping, bump all projects together.

**GitHub Packages — dependency resolution:** Add to `pom.xml` `<repositories>`:
```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/casehubio/*</url>
  <snapshots><enabled>true</enabled></snapshots>
</repository>
```

**Cross-project SNAPSHOT versions:** All casehubio artifacts are `0.4-SNAPSHOT` resolved from GitHub Packages.

## Dependencies

Upstream (this repo depends on):
- `casehubio/connectors` — connector tools and ref implementations
- `casehubio/platform` — MCP infrastructure, dispatch, schema generation

Downstream dependents: none (leaf repo)
