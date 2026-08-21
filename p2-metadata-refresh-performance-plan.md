# Plan: p2 metadata refresh performance

## Problem

A programmatic update check that refreshes repository metadata takes 9.8 seconds against a 10 repository configuration (7 remote).
The same check served from p2's in-memory cache takes 0.22 seconds.
The whole difference is network, and it is dominated by request *count*, not by per-request latency: individual index fetches measure 0.27s to 0.62s with curl.

The workload that exposes this is an MCP server driving update checks unattended and often, rather than a human clicking Check for Updates.
For a targeted check of one known unit, only one of the 7 remote repositories can possibly supply an update.

Measurements come from one developer machine on one network against download.eclipse.org.
Treat "7 remote repositories plus recursive composite children for a single unit query" as the shape of the problem, not 9.8s as a benchmark.

## Where the requests come from

Per repository node, per repository manager, a refresh performs:

1. `removeRepository`, which broadcasts `REMOVED` (`AbstractRepositoryManager.java:1059`).
2. `loadRepository` -> `loadIndexFile`, an unconditional full GET of `p2.index`, never cached and never conditional (`AbstractRepositoryManager.java:786-796`).
3. `CacheManager.createCache`, which does `transport.getLastModified(content.jar)` (one round trip) and then a full GET, because step 1 just deleted the cache file.

That is at least three round trips per node per manager, one of them a full body download.
With 7 remote roots plus roughly 7 composite children, times two managers, the configuration above issues on the order of 84 round trips, all serial.

## Findings

Each finding is grounded in the source in this repository.
Finding 2 is a code reading and needs a test to confirm before it is treated as a bug report.

### 1. Refresh deletes its own cache, so the staleness check can never save anything

`CacheManager` registers a listener that deletes the local cache file on a `REMOVED` metadata repository event (`CacheManager.java:376-378`).
`basicRefreshRepository` starts by calling `removeRepository`, which broadcasts exactly that event (`AbstractRepositoryManager.java:209-217`).
So every refresh deletes `content.jar` from the cache and then re-downloads it in full, even when the remote `lastModified` is unchanged.
The entire stale-checking logic in `CacheManager.createCache` is unreachable on the refresh path.

`CacheManager.deleteCache` iterates `knownPrefixes`, which holds both `content` and `artifacts`.
`ColocatedRepositoryTracker.refreshRepositories` refreshes the artifact repository first and the metadata repository second (`ColocatedRepositoryTracker.java:96-97`), so the metadata removal deletes the `artifacts.jar` cache file downloaded milliseconds earlier.
On a repeated-check workload that guarantees a full `artifacts.jar` re-download every time.

### 2. Recursive composite refresh appears to be one generation stale

`basicRefreshRepository(parent)` does, in order:

1. `removeRepository(parent)`.
2. `loadRepository(parent)`, whose `CompositeMetadataRepository` constructor calls `addChild` for each child (`CompositeMetadataRepository.java:92-93`), which calls `manager.loadRepository(child)` (`CompositeMetadataRepository.java:165`) and stores the returned instance in `loadedRepos` (`CompositeMetadataRepository.java:176`).
   At this point the children have not been refreshed, so these are the stale instances.
3. For each child, `basicRefreshRepository(child)` (`AbstractRepositoryManager.java:226`), which removes the child from the manager and registers a fresh instance.

Nothing updates the parent's `loadedRepos` after step 3.
There is no repository event listener anywhere in `org.eclipse.equinox.p2.metadata.repository`, and children are registered with `enabled=false` and `PROP_SYSTEM=true`, so the manager never enumerates them independently.
`CompositeMetadataRepository.query` iterates `loadedRepos`.

If this reading holds, the expensive child refresh produces objects the composite never queries, and the composite serves the previous refresh's child metadata.
That is a correctness bug first and a performance bug second.

### 3. No parallelism on the load or refresh path

Every loop is serial and every iteration is a blocking network round trip:

- `ColocatedRepositoryTracker.refreshRepositories`, per location (`ColocatedRepositoryTracker.java:93`)
- `basicRefreshRepository`, per composite child (`AbstractRepositoryManager.java:225`)
- `CompositeMetadataRepository` constructor, per child (`CompositeMetadataRepository.java:92`)
- `LoadMetadataRepositoryJob.doLoad`, per location (`LoadMetadataRepositoryJob.java:128`)

Searching `org.eclipse.equinox.p2.repository`, `org.eclipse.equinox.p2.metadata.repository`, `org.eclipse.equinox.p2.operations` and `org.eclipse.equinox.p2.ui` for `ExecutorService`, `CompletableFuture`, `ForkJoin` or parallel streams returns nothing.

`enterLoad` / `exitLoad` already lock per location and treat the owning thread as reentrant, so concurrent loads of *distinct* locations are supported by the existing design.

### 4. No conditional requests

There is no `If-Modified-Since` or `If-None-Match` anywhere in the codebase.
p2 emulates a conditional GET as HEAD-then-GET, which costs two round trips where HTTP offers one.
`FileReader` already sets per-request headers through ECF's `REQUEST_HEADERS` option (`FileReader.java:97`), so a conditional GET honouring 304 is mechanically feasible.

`p2.index` benefits most: it is fetched with an unconditional full GET for every node in both managers, and it essentially never changes.

### 5. Refresh is all or nothing, and refreshes more than the caller needs

`RepositoryTracker.refreshRepositories` takes a location array, so per-repository scoping is already possible for callers.
But there is no notion of "this location was checked N seconds ago": `RepositoryInfo` carries no timestamp, so an unrelated stale repository forces a refetch of one refreshed seconds ago.

Separately, `ColocatedRepositoryTracker.refreshRepositories` always refreshes both the artifact and the metadata manager.
An update *check* never reads artifact metadata, so half that work is unconditionally wasted.

## What the client should do, independent of any p2 change

p2 already exposes everything needed to scope a targeted check, and the honest answer to "is the client asking for the wrong thing" is yes.

- `UpdateOperation(session, Collection<IInstallableUnit>)` scopes to named units.
- `ProvisioningContext.setMetadataRepositories(URI...)` restricts resolution to named repositories.
  `getLoadedMetadataRepositories` loads only what is in that array (`ProvisioningContext.java:210`), so unlisted repositories are never contacted.
- `ProfileChangeOperation.setProvisioningContext` attaches the context to the operation.
- `ProvisioningContext.getInstallableUnitSources(ius, monitor)` (`ProvisioningContext.java:319`, since 2.8) returns which repositories hold which of the given units, resolving through composite children and references.
  That is the "which repositories could satisfy these units" answer, and it should be computed once and cached.
- `IMetadataRepositoryManager.refreshRepository(uri, monitor)` refreshes a single location, metadata only.
  The client should never refresh the artifact manager for a check.

Doing this reduces the work from about 14 nodes across two managers to one node in one manager, which is consistent with the "one repository and 0.3 seconds" estimate.
This is the fastest path to a fix and requires no p2 release.

## Fix plan

Five phases, each a separate PR against `eclipse-equinox/p2`, ordered so that each is independently valuable and independently revertable.
Phases 1 and 2 are small and self-contained.
Phase 3 is the biggest wall-clock win.
Phase 4 is the biggest request-count win.

Per the repository's contribution habits, each PR is a single commit, amended rather than appended when review feedback arrives.
Build every touched bundle locally with `mvn verify -pl :<bundle> -Pbuild-individual-bundles -DskipTests` before pushing, since unused imports are compile errors here.

### Phase 1: stop destroying the cache on refresh

Replace the remove-then-reload dance in `basicRefreshRepository` with an explicit invalidation that drops the cached in-memory repository instance without broadcasting `REMOVED`.

Add a package-private `invalidate(URI location)` to `AbstractRepositoryManager` that clears `RepositoryInfo.repository` while preserving the entry itself, its nickname, its system property and its enablement state.
`basicRefreshRepository` then calls `invalidate` instead of `removeRepository`, and the save-and-restore of nickname, system and enabled state in its `finally` block goes away with it.

Only two consumers of `REMOVED` exist, and both are compatible with this:

- `CacheManager.java:376`, which deletes the cache file.
  That deletion is correct when a user removes a repository and wrong when p2 refreshes one, which is exactly the behaviour change wanted.
- `ProvUIProvisioningListener.java:122`, which drives UI refresh.
  The UI already gets `signalRepositoryOperationStart` / `signalRepositoryOperationComplete` batching from `ColocatedRepositoryTracker`, so it does not depend on the spurious `REMOVED` + `ADDED` pair.

With the cache file preserved, `CacheManager.createCache`'s existing `lastModified` comparison does its job: an unchanged repository costs one `getLastModified` round trip instead of a full download.

Files: `AbstractRepositoryManager.java`.
Tests: extend `CacheManagerTest` with a case asserting the cache file survives `refreshRepository` and that an unchanged repository is not re-downloaded.
Add a case asserting nickname, system property and enablement survive a refresh, which the current `finally` block preserves by hand and the new code must preserve structurally.
Risk: low, but the event change is observable API behaviour and should be called out in the PR description.

### Phase 2: fix composite child refresh ordering

Invert the order in `basicRefreshRepository` so children are invalidated *before* the parent is loaded, not refreshed after.

Concretely: if the currently registered repository at `location` is an `ICompositeRepository`, walk its children and invalidate each one (phase 1's `invalidate`) before loading the parent.
Then load the parent once, and let the composite constructor pull each child through `manager.loadRepository`, which now misses the in-memory cache and revalidates against the network.
The explicit recursive `basicRefreshRepository(childLocation, monitor)` call at `AbstractRepositoryManager.java:226` is then deleted.

This removes an entire redundant traversal of the composite tree and, if finding 2 is confirmed, fixes the staleness.

Carry a visited set through the recursion so a child shared by two composites, or a cyclic composite, is invalidated once rather than repeatedly.

Files: `AbstractRepositoryManager.java`.
Tests: in `CompositeMetadataRepositoryTest`, build a composite over a local child, refresh, mutate the child on disk to add an IU, call `refreshRepository` on the *parent*, and assert the parent's query returns the new IU immediately rather than after a second refresh.
Add the mirrored case in `CompositeArtifactRepositoryTest`.
Run this test against unmodified `master` first: it is the confirmation that finding 2 is real, and if it passes unmodified then finding 2 is wrong and this phase reduces to the redundant-traversal cleanup only.
Risk: medium. This changes composite refresh semantics, and atomic composite loading (`PROP_ATOMIC_LOADING`, `shouldFailOnChildFailure`) interacts with failure handling during child load.

### Phase 3: parallelise the load and refresh loops

Introduce a small bounded executor for independent repository fetches and use it in the four serial loops listed in finding 3.
A fixed pool of 4 to 8, sized from a preference with a conservative default, is enough: these are IO-bound and download.eclipse.org should not be hit harder than that from one IDE.

Start with the two top-level loops, which are the safe ones:

- `ColocatedRepositoryTracker.refreshRepositories` (`ColocatedRepositoryTracker.java:93`)
- `LoadMetadataRepositoryJob.doLoad` (`LoadMetadataRepositoryJob.java:128`)

Then, in a follow-up commit within the same PR or a separate one, the composite child loop in the `CompositeMetadataRepository` constructor (`CompositeMetadataRepository.java:92`).

Known hazard: `enterLoad` records the owning *thread* and treats it as reentrant.
Today a composite loads its children on the same thread that holds the parent's lock, so reentrancy covers cycles.
Loading children on other threads breaks that: with a cyclic composite, thread A can hold the parent lock while waiting for a child, and thread B can hold the child lock while waiting for the parent.
Mitigation, in order of preference:

1. Key reentrancy on a logical load context (a set of locations in progress, propagated to worker tasks) rather than on `Thread.currentThread()`.
2. Or keep composite descent single-threaded in this phase and parallelise only the top-level loops, accepting that two top-level composites that reference each other remain a theoretical deadlock and are already pathological.

Also required: progress monitor handling.
`SubMonitor` is not thread safe, so each parallel task needs its own child monitor created up front, and cancellation must be checked per task.
Error handling must stay per repository, matching today's behaviour where one failed repository does not abort the others.

Files: `ColocatedRepositoryTracker.java`, `LoadMetadataRepositoryJob.java`, `CompositeMetadataRepository.java`, `CompositeArtifactRepository.java`, `AbstractRepositoryManager.java` if the lock keying changes.
Tests: a load of N slow local repositories completes in materially less than N times the single-repository time; failure of one repository still yields the same accumulated status; cancellation still returns `CANCEL_STATUS` promptly.
Risk: medium to high, and the highest-value change. It helps everyone who clicks Check for Updates, not just programmatic clients.

Expected effect on the measured configuration: roughly 9.6s to 1.5-2s, with no change in semantics.

### Phase 4: conditional requests

Two independent changes, both in the transport and cache layer.

First, `p2.index`.
Give it the same cache treatment the repository documents get: store it under the agent's cache area keyed by location, and revalidate rather than refetch.
This removes one full GET per node per manager.

Second, `CacheManager.createCache`.
Replace HEAD-then-GET with a single conditional GET that sends `If-Modified-Since` derived from the cache file's `lastModified` and treats a 304 as "cache is current".
`FileReader` already threads per-request headers through ECF's `REQUEST_HEADERS` option (`FileReader.java:97`), so the plumbing exists; the work is surfacing a conditional download in the `Transport` interface and handling the 304 status distinctly from an error.

Keep the existing HEAD-then-GET as the fallback when the server's response does not permit the conditional path, so a misconfigured mirror does not regress into always-refetching.

Combined with phase 1, a refresh of an unchanged repository becomes a single 304 per node.

Files: `Transport.java`, `RepositoryTransport.java`, `FileReader.java`, `FileInfoReader.java`, `CacheManager.java`, `AbstractRepositoryManager.java`.
Tests: `CacheManagerTest` against a local test server that returns 304, asserting no body transfer and a preserved cache file.
Risk: medium. Touches the transport contract, and mirror behaviour in the wild is inconsistent, hence the fallback.

### Phase 5: per-repository staleness window

Record a last-refreshed timestamp per location in `RepositoryInfo` and persist it alongside the existing repository preferences.
Add an opt-in minimum refresh interval: if a location was refreshed within the window, `refreshRepository` returns the cached repository without network work.

Default the window to 0 so behaviour is unchanged unless configured.
Expose it as a preference and as a flag on the refresh call so a caller can say "refresh, but not if you did so in the last minute".

This is the change most directly aimed at the unattended, frequently-repeated-check scenario, and it is ranked last because phases 1, 3 and 4 help every user while this one helps a specific caller pattern.

Files: `AbstractRepositoryManager.java`, `IRepositoryManager.java` if a flag is added, plus the preference plumbing.
Risk: low, given the default preserves current behaviour.

### Optional: a metadata-only refresh on RepositoryTracker

`ColocatedRepositoryTracker.refreshRepositories` refreshing both managers is correct for its stated contract, so changing it outright would be a behaviour break.
Adding a variant that refreshes metadata only, and using it from the update-check path, halves the work for the common case.
Lower priority than the above, and a client can already achieve it by calling `IMetadataRepositoryManager.refreshRepository` directly.

## What not to do

Do not trust a composite's own `p2.timestamp` to skip descending into children.
That timestamp describes `compositeContent.xml`, and the entire point of a composite pointing at a moving child, such as `4.41-I-builds`, is that the child changes without the parent being rewritten.
Skipping the descent on an unchanged parent timestamp would silently serve stale metadata.
Phase 4 makes the descent cheap enough that the shortcut is not needed.

Do not narrow refresh to "repositories that supply currently installed units".
In a normal Eclipse install that set includes the Platform and Orbit repositories, so it saves almost nothing.
The useful narrowing is "repositories that could satisfy the specific units under consideration", which is what the client-side section above describes and what `getInstallableUnitSources` computes.

## Measurements to take before and after

From the live IDE, for one forced refresh of the 10 repository configuration:

- The full HTTP request log: count, method and URL of every request.
  This confirms or corrects the ~84 estimate and should show `p2.index` fetched twice per node.
- Wall-clock time of the same programmatic update check, cached and refreshed, as the headline number.
- After phase 2's test exists, whether a composite returns a child's newly added IU immediately after `refreshRepository` or only after a second refresh.

Re-run the request log after each phase.
Request count is the metric that matters; wall clock on one network is the metric that is easy to misread.
