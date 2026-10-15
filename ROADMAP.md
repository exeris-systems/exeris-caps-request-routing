---
title: "exeris-caps-request-routing — Roadmap"
type: roadmap
visibility: public
owning-repo: exeris-caps-request-routing
status: active
last-verified: 2026-10-15
---

# exeris-caps-request-routing — Roadmap

Status: **poc**. Version line: **0.1.x**. Licence: Exeris Commercial License (ADR-023), entitlement id
`exeris-caps-request-routing`.

Milestones are versions, not dates. Dates appear only on what has shipped; nothing has shipped yet.

> **`1.0.0` is not on this roadmap.** It is a gate, described in the last section. Status moves
> `poc` → `snapshot` → `released`.

---

## Where this sits

Layer 3 gateway policy cap. Provides `RequestRouter`: a radix-tree matcher over exact, prefix and
`{template}` routes, with method filtering, deterministic precedence (`order`, then `id`), path rewrite
(`stripPrefix`, `prefixRewrite`, `exactRewrite`) and request/response header mutation, built through
`RequestRouters.builder()`. Zero runtime dependencies; no `@Requires`. 13 tests across three classes.

Gaps between the inventory and the code, found in the code:

- HLA §3.2 names the service `RoutingPolicy` and lists `@Requires policy-chain, route-registry`. The code
  provides `RequestRouter` and requires nothing; the route table is immutable, built once from a list.
  There is no `route-registry` cap and no reload path.
- The 0.1.0 code refuses to route non-canonical paths (dot segments, encoded `/` and `\`, control
  characters) and matches prefixes on segment boundaries.
- The routing result is consumed only through the SKU's `RoutingGatewayPolicy` and
  `RoutingReverseProxyHandler`; the cap itself never touches an exchange.

**Upstream constraints this repo does not own:**

| Constraint | Effect here | Owner |
|---|---|---|
| Kernel licence gate (ADR-088, ADR-089) in kernel `0.13.0-SNAPSHOT` | `EntitlementRequirement` resolvable only from that line | `exeris-kernel` |
| `RequestFilterChain` contract (ADR-099) | 0.2.0 binds to it | `exeris-caps-service-boundary-core` |
| ADR on the response-side seam (gateway-core 0.2.0) | Entry condition: no policy cap starts its 0.2.0 before it is accepted | `exeris-caps-gateway-core`, `exeris-docs` |
| `RouteMatch` name clash: this cap and service-boundary-core both define one | The binding must map one to the other | both caps |
| HLA §3.2 row differs from the code | Registry describes `RoutingPolicy` and two requirements that do not exist | `exeris-docs` |

---

## 0.1.0 — the code PR

- [ ] Defects found in the pre-import review are fixed in the import, each with a test
- [ ] Code imported; repository files (`README`, `CLAUDE.md`, `LICENSE`, `SECURITY`, `CONTRIBUTING`, `CODEOWNERS`, ADR link stubs) added
- [ ] `@Provides(RequestRouter)` at contract `0.1.0`
- [ ] Kernel `0.13.0-SNAPSHOT`; SDK `0.12.0` and tooling `0.10.0` pins aligned with the sibling caps
- [ ] Artefacts stay `0.1.0-SNAPSHOT` until kernel `0.13.0` is released; a non-snapshot `0.1.0` is cut only after that
- [ ] Licence enforcement: `RequestRoutingEntitlement` (id `exeris-caps-request-routing`), `META-INF/services`
      registration, TCK test and boot test against the kernel's Phase 0 gate
- [ ] `build.yml` green on JDK 25 and 26; `cap-manifest.json` committed through the two-pass build

**Exit gate:** `mvn verify` green on both JDK rows; entitlement test fails without the id.

## 0.2.0 — bind to `RequestFilterChain`

Follows the gateway-core PR that replaces its `PolicyChain`.

- [ ] Optional `@Requires(RequestFilterChain, "[0.1.0,0.2.0)")` and a `RequestRoutingServiceBoundaryBinding`
      in `api`, on the pattern of `exeris-caps-rate-limiting`; replaces the SKU-side `RoutingGatewayPolicy`
- [ ] Response-header mutation (`transformResponseHeaders`) needs a response-side seam that
      `AdmissionFilter` does not offer; follows the response-side ADR (entry condition: accepted before
      this milestone starts; see gateway-core 0.2.0)
- [ ] Consume the canonical path produced once before the chain (gateway-core 0.2.0 canonicalisation ADR);
      until then the router refuses non-canonical paths on its own, as the 0.1.0 code does
- [ ] Align `HLA §3.2` (service name, requirements) through an `exeris-docs` PR

## 0.3.0 — Independent security review

- [ ] Covered by the API Gateway SKU review (one review; scope: the SKU and every composed cap); this
      repository's public API and documented behaviour are listed in that scope
- [ ] Threat model written and committed alongside the code
- [ ] Findings handled through `SECURITY.md` private reporting
- [ ] Fixes released, each with a regression test

## 0.4.0 — re-measurement and snapshot

- [ ] Match-path measurement in the `exeris-benchmarks` harness on the reference profile, published there; no figure stated here
- [ ] Route-table reload decision recorded: stay immutable (rebuild and swap) or wait for `route-registry`
- [ ] Snapshot artefact on GitHub Packages; status `poc` → `snapshot`

---

## 1.0.0 — a gate, not a milestone

Entry condition: the API Gateway SKU routes real traffic through the post-0.2.0 binding, the review is
closed, and a decision on `route-registry` is made. Then `RequestRouter` freezes, a semver gate is added,
and the status becomes `released`.

## Deliberately out of scope

- Forwarding and upstream connection handling: the SKU and `upstream-pool`.
- Dynamic route sources (files, control plane): `route-registry` and `admin-control-plane`, not in this repository.
