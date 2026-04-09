# AGENTS.md — AI Agent Framework Reference

## Goal

Create a token-efficient reference document that gives AI agents everything they need to build applications with Brace. No agent has Brace in its training data, so this document is the entire knowledge base.

## Design Decisions

- **Audience:** AI agents building apps with Brace (not evaluating it, not developing the framework itself)
- **File name:** `AGENTS.md` — agent-agnostic, not tied to any specific AI tool
- **Structure:** Single flat document, ~400-500 lines. Brace's API surface is small enough that splitting would be premature.
- **Style:** Concept-first with inline examples. Each section: one mental-model sentence, a code example of the most common usage, then a compact method/API reference. No design rationale or "why" explanations.
- **Content source:** Derived from existing README.md and CLAUDE.md, verified against source code for completeness.

## Distribution

- **Source of truth:** `AGENTS.md` in the Brace repo root
- **New projects:** `brace new` copies AGENTS.md content into the generated CLAUDE.md. This means each project gets a snapshot matching the framework version it was built with — no risk of reading docs for a newer version.
- **Maintenance:** CLAUDE.md (the framework's own dev context) includes a note reminding contributors to update AGENTS.md and README.md when changing public API.

## AGENTS.md Sections

```
# Brace Framework Reference

## Overview
  One sentence: what it is, how it works.
  "Read main() — it's the map to everything."

## Build & Run
  mvn compile, mvn test, dev mode, prod mode

## App Setup
  Brace.app() builder methods, typical main() example

## Routing
  Handler types (Handler, DbHandler, SessionHandler, FullHandler)
  Registration, path params, grouping, middleware

## Request
  Params, headers, body, file uploads, isHtmx()

## Responses
  Result, View, Json, Redirect with method signatures

## Database
  Method reference (find, query, insert, etc.)
  HQL with ? params, per-request transactions, withSession

## Forms & Validation
  Record-based binding, annotations, error flow

## Templates
  JTE basics, parameter passing, layouts, partials convention

## Sessions
  API (set, get, has, clear), cookie-based, stateless

## CSRF
  Auto-validated on POST/PUT/DELETE, skipped for JSON

## Cache
  set/get/delete, route-level wrapping, tag invalidation

## Jobs
  Recurring (in-memory) vs durable (database-backed), scheduling API

## Mailer
  Sending, dev-mode capture, templates

## Storage
  S3-compatible, put/delete/url

## WebSocket
  app.ws(), rooms, broadcast

## Ops
  /ops/status, /ops/dashboard, Ed25519 auth

## Custom Metrics
  counter, gauge, timer

## Testing
  Brace.test() harness, withDb, assertions

## Config
  File format, mode prefixes, env var substitution

## Common Patterns
  Adding endpoints, entities, forms, htmx — step-by-step recipes
```

## Changes Required

### 1. New file: `AGENTS.md` (repo root)
The full framework reference, ~400-500 lines.

### 2. Update `README.md`
Add one line near Quick Start pointing agents to AGENTS.md.

### 3. Update `CLAUDE.md`
Add to Common Patterns: "When changing public API, update AGENTS.md and README.md."

### 4. Update `ProjectGenerator.java`
Replace the current CLAUDE.md generation (lines 208-219) with code that reads AGENTS.md content from the classpath and writes it as the new project's CLAUDE.md.

### 5. Add `AGENTS.md` as a classpath resource
Copy to `src/main/resources/brace/agents-reference.md` (or similar) so it's bundled in the jar and available to ProjectGenerator at runtime.

### 6. Update or remove `ClaudeMdGenerator.java`
Currently generates a thin stub. Either update to use AGENTS.md content or remove if no longer needed.
