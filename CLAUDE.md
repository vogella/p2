# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository

Eclipse Equinox **p2** — the provisioning platform used by Eclipse and OSGi-based applications. Contains the dependency resolver (SAT4J-based), metadata/artifact repository implementations, director/planner/engine, touchpoints, UI, and publisher. Built with Maven + Tycho against the `eclipse-platform-parent` POM (expected at `../eclipse-platform-parent`).

The repo is a multi-module Tycho build. Top-level layout:

- `bundles/` — ~44 OSGi bundles (source plug-ins). Each has `META-INF/MANIFEST.MF`, `build.properties`, and a Tycho `pom.xml`.
- `features/` — Eclipse features that group bundles for delivery.
- `releng/org.eclipse.equinox.p2.setup/` — Oomph setup model for the Eclipse IDE dev environment.
- `docs/` — conceptual docs (Concepts, Engine, Installable Units, Query Language, p2.index, Trust Settings).

## Build & Test

Builds require Java 21 (Temurin in CI) and Maven. The root build aggregates all bundles and features.

```bash
# Full build without tests
mvn clean verify -DskipTests

# Full build with unit tests
mvn clean verify

# Build a single bundle (run from inside the bundle directory; needs the
# build-individual-bundles profile so the parent POM is resolved remotely)
mvn clean verify -Pbuild-individual-bundles

# Run a single test bundle's tests
mvn -f bundles/org.eclipse.equinox.p2.tests/pom.xml verify
```

The CI build (`Jenkinsfile`) runs under Xvnc (UI tests need a display) with extra profiles:

```bash
mvn clean verify -Pbree-libs -Papi-check -Pjavadoc \
    -Dcompare-version-with-baselines.skip=false \
    -Dmaven.test.failure.ignore=true -T 1C
```

- `-Papi-check` enables PDE API Tools; breakages surface as API analysis findings, which gate CI (`qualityGates` in `Jenkinsfile`).
- `-Pjavadoc` turns on javadoc generation; `failOnJavadocErrors=true` in the root POM means javadoc errors fail the build.
- `compare-version-with-baselines.skip=false` enforces semantic-version bumps relative to the released baseline — if you change public API, bump the bundle version (see `forceQualifierUpdate.txt` touch-files used to force qualifier changes).

Tests are Tycho surefire / `eclipse-test-plugin` modules. `org.eclipse.equinox.p2.tests` is the main test bundle; its entry point is `org.eclipse.equinox.p2.tests.AutomatedTests` (configured as `testClass` in its `pom.xml`). To run a single test interactively, use the `.launch` files checked into `bundles/org.eclipse.equinox.p2.tests/` from the Eclipse IDE, or pass `-Dtest=<ClassName>` to surefire.

## Architecture

p2 is intentionally modular — there is no "the p2 agent" bundle. An **agent** is assembled from the bundles needed for a given host (desktop IDE, headless server, embedded device). Future work should preserve this separation of concerns; do not collapse layers just because the call graph allows it.

The core pipeline, as reflected in the bundle layout:

1. **Metadata** (`org.eclipse.equinox.p2.metadata`, `.metadata.repository`) — **Installable Units** (IUs) describe what can be installed (capabilities, requirements, touchpoint data). IUs are *not* the artifacts themselves.
2. **Artifacts** (`org.eclipse.equinox.p2.artifact.repository`, `.repository`, `.repository.tools`) — actual bundle JARs, executables, etc. Artifact and metadata repositories are independent: an IU in one repo does not imply its artifact lives in the same repo. Mirroring (not "downloading") is the transport model.
3. **Director** (`org.eclipse.equinox.p2.director`, `.director.app`) — high-level API that drives a provisioning request. Invokes the planner, then the engine.
4. **Planner** — given a profile's current state + a desired state + available IUs, produces the list of install/update/uninstall operations. Uses SAT4J for dependency resolution.
5. **Engine** (`org.eclipse.equinox.p2.engine`) — executes the planner's output through a sequence of **phases** (Collect, Unconfigure, Uninstall, Fetch, Install, Configure, …). Each phase delegates to **touchpoints** for runtime-specific work.
6. **Touchpoints** (`org.eclipse.equinox.p2.touchpoint.eclipse`, `.touchpoint.natives`) — bridge the engine to a concrete runtime. The Eclipse touchpoint knows how Equinox stores bundles; the native touchpoint handles OS-level actions.
7. **Profiles** — the target of install/management operations. A profile is a set of IUs; the same IU can live in many profiles simultaneously.
8. **Publisher** (`org.eclipse.equinox.p2.publisher`, `.publisher.eclipse`, `.updatesite`) — generates IUs and artifact repos from Eclipse features, products, update sites, etc.
9. **UI** (`org.eclipse.equinox.p2.ui`, `.ui.sdk`, `.ui.importexport`, `.ui.discovery`) — end-user and SDK-facing wizards/views.
10. **Operations** (`org.eclipse.equinox.p2.operations`) — the programmatic API most clients should use rather than invoking the planner/engine directly.

Transport is pluggable; `org.eclipse.equinox.p2.transport.ecf` is the production implementation (ECF filetransfer). `org.eclipse.equinox.p2.garbagecollector` reclaims IUs/artifacts no longer reachable from any profile's root set. `org.eclipse.equinox.p2.reconciler.dropins` is the bundle behind the `dropins/` folder auto-install mechanism. `org.eclipse.equinox.simpleconfigurator` drives bundle start-up order from `bundles.info`.

When changing behavior, read `docs/Concepts.md`, `docs/Engine.md`, and `docs/Installable_Units.md` first — they encode the vocabulary (profile, IU, touchpoint, phase, action, requirement, capability) used throughout the code. `docs/Query_Language_for_p2.md` covers the IU query language (`IQuery`, `QueryUtil`). `docs/p2.index.md` and `docs/Trust_Settings.md` document repository layout and artifact-trust configuration respectively.

## Conventions

- Bundle versions follow OSGi semantic versioning and are enforced by API Tools baseline comparison in CI. Bump the minor version when adding API, major when breaking it; the build will fail otherwise.
- Public API lives in packages without `internal` in the name. Anything under `...internal...` is not API and may change without notice — changes there don't require version bumps but also should not be depended on from outside the bundle.
- Every bundle has an `about.html` (EPL-2.0 notice) and most have a `forceQualifierUpdate.txt` used to nudge the build qualifier when the source hasn't otherwise changed.
