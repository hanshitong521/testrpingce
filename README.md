# Agent Capability Benchmark v1 — Mini Task Queue

This is a small Java 17 repository intended for comparing coding agents under the same model.

## Environment

- JDK 17 or newer
- No Maven/Gradle or third-party dependencies
- Windows: `powershell -ExecutionPolicy Bypass -File .\run-tests.ps1`
- macOS/Linux: `./run-tests.sh`

## Repository map

- `TaskQueue.java`: queue behavior and state transitions
- `TaskSnapshot.java`, `TaskClaim.java`, `TaskStatus.java`: public API types
- `TimeSource.java`: injectable clock
- `TaskQueueTest.java`: visible dependency-free regression tests

The visible tests are intentionally not exhaustive. Follow the task prompt supplied with this repository, inspect the implementation and tests, then add focused tests for important boundary cases.

## Constraints

- Keep the public API compatible unless a change is essential and clearly justified.
- Keep the implementation in-memory and dependency-free.
- Do not use `Thread.sleep` to test time-dependent behavior; use the injected clock.
