# Support files

This directory contains repository-only assets that are not part of the Firefly
runtime or published Maven artifacts:

- `benchmarks/` contains local performance and fault-injection workspaces.
- `skills/` contains repository-specific development guidance.

Runtime configuration stays under the root `config/`; generated H2 data stays
under `data/`; database and release scripts stay under `scripts/`; and the
Spring Boot compatibility consumer remains under `compatibility/` because CI
invokes those paths directly.
