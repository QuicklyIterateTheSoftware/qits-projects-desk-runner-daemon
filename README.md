# qits-projects-desk-runner-daemon

The **qits front-desk runner**: a container you start on any Linux machine with docker so that
machine runs qits project **front desks** — the always-on `project-agent` container of each project
qits-projects assigns it. It registers once, holds one outbound connection to qits-projects through
the platform's public edge, and keeps the desks it is told about converged as plain `docker run`
containers on this machine. A desk stays on the runner that first ran it: its `/workspace` volume
lives here.

qits-projects keeps the desk — which project has one, on which runner, with which spec. The runner
only creates, starts, stops and removes the desk containers it is told to, and reports what it has.

    ./mvnw verify                                                          # the JVM suite
    docker build -t qits-projects-desk-runner:dev -f docker/Dockerfile .   # the image (see docker/Dockerfile)

## What the runner does with a desk

The server's `estate` is the full list of this runner's desks, each with a desired state, and the
runner converges every one of them (`DeskRunnerDomain`, `DeskLauncher`):

- **RUNNING, no container** — the image is pulled when absent (under the runner's own registry
  login, never your docker config), the desk volume and the node's `dot-claude`/`m2`/`pnpm` volumes
  are created labelled, and the desk is `docker run` with `--init`, `--restart unless-stopped`, the
  default bridge, no extra host and no docker socket.
- **RUNNING, exited** — `docker start`. **RUNNING, another spec hash** — stopped, the container
  removed (never its volume), run again with the new spec.
- **STOPPED** — `docker stop`; the container and its volume stay.
- A desk the estate no longer lists is stopped and removed at once, its volume at the next
  housekeeping pass — and nothing at all is removed before an estate has arrived on the current
  connection. A `remove` frame removes the container and its volume at once.

It reports an `inventory` after every convergence and every 60 s, asks for new desks (`reserve`)
only while fewer desks run than it has slots, probes the node's Claude login (`loginState`) at the
greeting and on request, and answers a `healthCheck` by running the requested image once.

## Installing a runner (for the person at the machine)

A runner is **one docker container**: the static `qits-projects-desk-runner` binary in the image
`registry.qits.<domain>/qits/qits-projects-desk-runner:<version>`, started with docker's own restart
policy (`unless-stopped`) as its only supervisor and the host's docker socket mounted so it can
start desks. No service to install, no binary on the host, no systemd unit. It is never on the
platform's internal network: it reaches the platform only through its public names.

### What you need

- **Linux on x86-64** with **Docker** installed and running (`docker version` answers).
- **Outbound HTTPS** to the platform's domain (`projects.qits.<domain>`, `idp.qits.<domain>`,
  `registry.qits.<domain>`). **No inbound port** is ever opened.
- **root**, or a user in the `docker` group — the install line uses `sudo`.
- Disk for the desks: each one's `/workspace` volume, the shared caches and the project-agent image
  live on this machine.

### Install

It is installed by **the one line from the projects runners page**:

1. In qits-projects, open **Runners**, create a runner and open its install panel.
2. **Copy the one line** it shows — once; it is gone on reload — and **paste it into a shell** on the
   machine. It looks like

       curl -fsSL -H 'Authorization: Bearer qits_tok_…' https://projects.qits.<domain>/projects/api/runners/install.sh | sudo env QITS_PROJECTS_DESK_RUNNER_URL=https://projects.qits.<domain> QITS_PROJECTS_DESK_RUNNER_ID=<id> QITS_PROJECTS_DESK_RUNNER_REGISTRATION_TOKEN=qits_tok_… QITS_PROJECTS_DESK_RUNNER_SLOTS=<slots> sh

The script is qits-runner-toolkit's template, rendered by qits-projects with this runner's
identity; it carries no secret and names no runner — the four values come from the line. Treat the
line as a secret until the runner has registered: the token is in it twice. It logs in to
`registry.qits.<domain>` with the token in a throwaway `docker --config` (your
`~/.docker/config.json` is never touched), pulls the runner image, replaces any earlier install of
this runner (a container labelled `qits.projects.desk.runner.process=<id>` — **never a desk**),
and starts `qits-projects-desk-runner-<id8>-<version>` with the docker socket and the state volume
`qits-projects-desk-runner-state-<id8>` mounted at `/var/lib/qits-projects-desk-runner`.

    docker logs -f qits-projects-desk-runner-<id8>-<version>

shows `projects-desk-runner registered as <id>` once, on the very first start (the token was
exchanged for the runner's own client, stored in `client.json` on the state volume at mode 0600),
and `projects-desk-runner connected slots=<n>` on every connection. The runner mints its access
token at `https://idp.qits.<domain>/idp/token` and dials
`wss://projects.qits.<domain>/projects/runners/socket`; both come from the registration answer.

### Updating

Nothing to do: the runner updates itself. qits-projects pins the runner version it hands out (the
version of the `qits-projects-desk-runner-protocol` jar it compiles against), and a runner that
connects with any other version is told to become it. **An update never stops a desk**: the
successor container starts at once and adopts the running desks by their label.

### The node namespace

Everything the runner creates is named from its id (`<id8>` its first eight characters) and carries
its labels, so it can share a machine with a CI runner, a workspaces runner and your own
containers.

| What | Name | Labels |
|---|---|---|
| the runner | `qits-projects-desk-runner-<id8>-<version>` | `qits.projects.desk.runner.process=<id>`, `qits.projects.desk.runner.version=<version>` |
| its state | volume `qits-projects-desk-runner-state-<id8>` at `/var/lib/qits-projects-desk-runner` | — |
| a desk (project `<projectId>`) | container and volume `qits-projects-desk-runner-desk-<projectId>-<id8>` | `qits.projects.desk.runner=<id>`, `qits.projects.desk.runner.project=<projectId>` (the container also `qits.projects.desk.runner.spec-hash=<hash>`) |
| the agent home and caches | volumes `qits-projects-desk-runner-<dot-claude\|m2\|pnpm>-<id8>` | `qits.projects.desk.runner=<id>` |

### Environment

The install script passes the first four; the rest are qits-runner-toolkit's, for an operator with
a reason. A successor inherits whatever its predecessor was given.

| Variable | Meaning | Default |
|---|---|---|
| `QITS_PROJECTS_DESK_RUNNER_URL` | qits-projects' public url, `https://projects.qits.<domain>`. | required |
| `QITS_PROJECTS_DESK_RUNNER_ID` | The runner id the runners page minted. | required |
| `QITS_PROJECTS_DESK_RUNNER_REGISTRATION_TOKEN` | One-time registration token, needed until registered; a different one later registers again. | required until registered |
| `QITS_PROJECTS_DESK_RUNNER_SLOTS` | How many desks this machine runs. Advertised; the runners page's number wins. | `1` |
| `QITS_PROJECTS_DESK_RUNNER_STATE_DIR` | Where `client.json` lives — the state volume's mount point. | `/var/lib/qits-projects-desk-runner` |
| `QITS_PROJECTS_DESK_RUNNER_DOCKER_BINARY` | The docker CLI. | `docker` (the image's) |
| `QITS_PROJECTS_DESK_RUNNER_DOCKER_TIMEOUT` | Seconds any one docker call may take. | `120` |
| `QITS_PROJECTS_DESK_RUNNER_ROLLOVER_TIMEOUT` | Seconds a successor has to take over. | `180` |
| `QITS_PROJECTS_DESK_RUNNER_SELF_UPDATE` | `false`: ignore updates; something else replaces this runner. | `true` |
| `QITS_PROJECTS_DESK_RUNNER_TELEMETRY_URL` | Where the runner's own log is shipped (OTLP). Empty switches it off. | derived: `https://observability.qits.<domain>/observability/api/otel` |
| `QITS_PROJECTS_DESK_RUNNER_PRINT_VERSION` | `1`: print the version and exit 0, needing nothing else. | unset |

The runner's log is `docker logs` on the machine, and also lands on the platform under
`_service/qits-projects-desk-runner`.

### Exit codes

The toolkit's, as for every runner: 0 retired or decommissioned; **2 a variable is missing or
unparseable — also the image run with no environment**; 3 registration unreachable or 5xx; 4 a
protocol version this runner does not speak; 5 registration refused (4xx); 6 the state directory or
`client.json` is unusable. `qits-projects-desk-runner health` is the container healthcheck: 0 while
`<state dir>/heartbeat` is younger than 60 s, 1 when missing or stale, 2 when the state directory
does not exist.

## Layout

- `projects-desk-runner-protocol/` — published as `eu.wohlben.qits:qits-projects-desk-runner-protocol`:
  the front-desk frames (`DeskRunnerMessage`, `DeskSpec`, `DeskRunnerCodec`), the socket path, the
  capability version, the vocabulary, the identity roots, and `DeskRunnerBinary.VERSION` — the image
  tag, so qits-projects' pin of this jar is the pin of the runner it hands out. Depends on
  `qits-runner-protocol` only.
- `projects-desk-runner/` — the binary: `Main`, `DeskRunnerIdentity`, `DeskRunnerDomain`, on
  `qits-runner-toolkit`.
- `docker/` — the native musl compile and the image (`Dockerfile`), and the toolchain
  (`Dockerfile.musl-builder`, a copy of qits-workspaces-runner-daemon's).
- `.config/qits/release.yml` — the gate (the suite, an image smoke test) and the release (the image,
  then the jar).

## The conversation

The lifecycle frames are qits-runner-protocol's, byte-identical to every runner's; the front-desk
frames are this repository's. A project id is a JSON string.

    runner → hello{runnerVersion, capabilityVersion, slots, capabilities, heldDesks}
    ack{slots, adoptedDesks} ← … estate{desks:[{projectId, desired, spec, specHash}], deskImage}
    runner → inventory{desks:[{projectId, state, specHash, detail}]}   (RUNNING|STOPPED|ABSENT|FAILED)
    reserve … take{projectId, desired, spec, specHash} | nothing       (desired: RUNNING|STOPPED)
    runner → launchFailed{projectId, detail}
    remove{projectId} → removed{projectId}
    probeLogin → loginState{state}                                      (PRESENT|ABSENT)
    healthCheck{requestId, image} → healthChecked{requestId, ok, detail}
    upgrade{version, image, sha256} … retire{reason, kind}
    quarantined{reason, since} … reinstated{by}

`spec` is `{image, env{}, user, memory, memorySwap, pidsLimit, cpus, oomScoreAdj}` — no network, no
extra hosts, no docker socket, no mounts; the runner names the volumes itself.

## Releasing

A release request gates on `./mvnw verify` and a smoke test of the built image (it prints this
tree's version when asked with `QITS_PROJECTS_DESK_RUNNER_PRINT_VERSION=1`, and exits 2 with no
environment). The release pushes the image first and publishes the protocol jar after it: the jar
is the pin qits-projects-service resolves, and it must not resolve before its image exists. See
`AGENTS.md` for the working rules.
