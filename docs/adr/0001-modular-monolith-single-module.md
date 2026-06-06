# ADR-0001: Modular monolith in a single Maven module, boundaries enforced by ArchUnit

- Status: accepted
- Date: 2026-06-06

## Context

The requirements mandate hexagonal architecture where domain and application
code stay free of Spring, LLM SDKs, diff libraries, Git and persistence
details. Boundaries can be enforced either by Maven multi-module compile-time
dependencies or by package rules verified with ArchUnit.

## Decision

Use one backend Maven module with the package layout
`domain` / `application(.port|.service)` / `adapter.*` / `config`, and enforce
the dependency rules with a build-breaking ArchUnit test suite.

## Consequences

- One `pom.xml` keeps the one-week build simple; quality gates configure once.
- Violations fail the build with a precise message, satisfying the mandated
  architecture tests directly.
- Trade-off: boundaries are test-enforced rather than compiler-enforced. The
  ArchUnit suite runs in the default `verify` lifecycle, so a violation cannot
  reach CI green.
