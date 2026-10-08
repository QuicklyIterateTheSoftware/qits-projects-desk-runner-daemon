# qits-projects-desk-runner-daemon — working notes

Read `README.md` first: it is the operator's view of what the runner does on a node. This file is
the working conventions on top of it. It deliberately mirrors qits-workspaces-runner-daemon's
`AGENTS.md`, the template this repository was copied from; where it differs, it says why.

## The rules that shape everything

**The runner is qits-runner-javalib's, embedded.** Registration, the bearer, the control socket,
the slot ledger, self-update, decommission, the heartbeat, housekeeping, the runner's own log
shipping and the install-script template are `eu.wohlben.qits:qits-runner-toolkit` (qits-771). This
repository supplies exactly what the toolkit asks an embedder for: a `RunnerIdentity`
(`DeskRunnerIdentity`, whose roots are `KnownRunnerNamespaces.PROJECTS_DESK`), a sealed frame set
with its `DomainCodec` (the protocol module), a `RunnerDomain` (`DeskRunnerDomain`) and a `main`
(`Main`). **Never fork or copy a toolkit class.** A case the toolkit's public API cannot express is
a gap fixed there, released, and pinned here.

**A clone of this repo alone builds and tests green** — given the platform's maven repository,
which the toolkit, `qits-runner-protocol` and `qits-containers-driver` are published to. No docker,
no prior `mvn install` elsewhere, no credentials. `./mvnw verify` is the gate.

**It compiles to a fully static musl GraalVM native image**, with the workspaces runner's toolchain
(`docker/Dockerfile.musl-builder`, a copy) and for its reason: a runner node is whatever Linux a
person had. No Jackson, no REST client, no docker SDK; every docker call goes through the toolkit's
process seam (`RunnerDocker`, `qits-containers-driver`), under a deadline.

**A healthy runner never exits**, and **a rollover never drains.** Desk containers are node state
that outlives the runner process: `DeskRunnerDomain.quiescent()` answers "no convergence in
flight", never "holds nothing", so the successor starts at once and adopts the desks by label.

**Nothing is removed before an `estate` has arrived on the current connection.** The estate is the
server's full list and it wins; an absent list is malformed, never "owns nothing".

## Module conventions

`eu.wohlben.qits.projectsdeskrunner.protocol` (the published jar
`qits-projects-desk-runner-protocol`) and `eu.wohlben.qits.projectsdeskrunner` (the binary), one
package per module.

**The protocol module depends on `qits-runner-protocol` and nothing else**, enforced by
`maven-enforcer-plugin`. qits-projects-service (Jackson) and the native runner (Vert.x) both link
it. Plain records, plain constructors, no annotations; fields are read through `WireMaps`.

**`Main` is the only CDI bean.** Everything below it is a plain class taking its collaborators as
constructor arguments; nothing reads configuration — the toolkit's `RunnerSettings` parses the
environment (`QITS_PROJECTS_DESK_RUNNER_*`).

**Every name on a node is derived**, from the identity (`RunnerIdentity.nodeName`/`nodeVolume`,
`OwnResources.labels`) — never from a frame and never a literal. A desk's container and its
`/workspace` volume are keyed on the PROJECT id.

## Testing

Plain JUnit 5. **No Mockito, no `@QuarkusTest`.** The runner is driven through the toolkit's own
fixtures: `FakeRunnerHost` (a real Vert.x server: register door, token endpoint, control socket)
and `FakeDocker` (a shell script the production process seam forks as docker), with
`RunnerRuntime.NodeOptions` shrinking the node's clocks. `DeskRunnerTest` is the lifecycle suite,
in the shape of the workspaces runner's `WorkspacesRunnerTest`; start a new case from it.

Test names are sentences describing the behaviour.

## Adding a front-desk frame or field

1. A record in `projects-desk-runner-protocol`, added to `DeskRunnerMessage`'s `permits` list.
2. `DeskRunnerProtocol.Type`/`Field` constants — never a bare string at a call site.
3. Encode and decode arms in `DeskRunnerCodec`, the round-trip list and the golden spelling in
   `DeskRunnerCodecTest` (the permitted-subclass check fails until you do).
4. Bump `DeskRunnerProtocol.CAPABILITY_VERSION` only if a peer must branch on it. A new field is
   not a bump: both codecs ignore unknown fields.
5. **Release this repository, then the maintenance bump moves the pin in qits-projects-service**,
   which handles the new case. This repository is the frames' only author.

The lifecycle frames (`hello`, `ack`, `heartbeat`, `backlog`, `reserve`, `nothing`, `upgrade`,
`retire`, `quarantined`, `reinstated`) are qits-runner-protocol's and are never declared here.

## Untrusted input

A desk's `DeskSpec` arrives over a socket and becomes an argv to a root-equivalent daemon:

- **The shape is the boundary.** No network, no extra host, no docker socket, no mounts, no
  free-form argv, no volume name — the runner names the volumes and where they land. A capability
  the record cannot express is a change to it.
- **The runner's own label namespace (`qits.projects.desk.runner.`) is the driver's.**
- **Every listing and removal selects by the runner's own label or a name it derived**, never
  host-wide and never by `name=`. The node is shared with other runners and a person's things.
- **A spec's environment is never logged**: it carries the desk's credentials.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why* — the tradeoff, the
alternative rejected, the failure it prevents. What the code does is the code's job.

## Building

Every maven invocation on the shared host goes through the lock:

    flock /tmp/qits-maven.lock ./mvnw -B -ntp verify
