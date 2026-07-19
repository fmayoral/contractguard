# ADR-0006: Flyway-managed schema with an optional PostgreSQL profile

- Status: accepted
- Date: 2026-07-19

## Context

The MVP created its H2 schema with Spring's `schema.sql` initialisation and
had no story for schema evolution or for running against a server-grade
database. Durable multi-user deployments need PostgreSQL, versioned
migrations, and a retention policy — without giving up the zero-setup
embedded default for local use.

## Decision

Manage the schema with Flyway using vendor-specific migration folders
(`db/migration/h2`, `db/migration/postgresql`) resolved via the `{vendor}`
placeholder. Keep embedded H2 (in PostgreSQL compatibility mode) as the
default; add a `postgres` Spring profile configured entirely through
`CONTRACTGUARD_DB_*` environment variables. Adapters use only SQL that both
engines accept — the run upsert is an update-then-insert (each run has a
single writer, enforced by per-repository exclusion), and generated keys are
requested by column name. `baseline-on-migrate` adopts databases created
before Flyway managed the schema. Retention is a domain-agnostic application
service that deletes finished runs past a configurable age, then their
events and artifacts.

## Consequences

- The same adapter code is exercised against both engines; a Testcontainers
  suite (`-Ppg`, requires Docker) proves the PostgreSQL path on every change.
- Schema changes from now on are additive Flyway migrations per vendor.
- Trade-off: two DDL copies must stay in step — acceptable while the schema
  is two tables, revisit if it grows.
- H2 remains the test default, so CI needs no database service.
