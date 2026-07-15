# ADR-0001: CorrugatedPackagingAdvisor ⊣ Corrugated Packaging Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1702` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1702` publishes an OSS blueprint for corrugated-
board/box plant **operations coordination** (production-batch board/
container-grade/quantity/burst-strength/edge-crush-test data logging,
corrugator/converting-line -- printer-slotter, flexo-folder-gluer,
die-cutter -- maintenance scheduling, safety-concern flagging, and
outbound corrugated-board-sheet/box shipment coordination). Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-1701` (Manufacture of
pulp, paper and paperboard): both are back-office coordination actors
for a fixed processing PLANT (not a field site) with heavy equipment
and a physical-safety dimension, and a central ground-truth
**production batch** entity independently gated alongside an
**equipment** entity. Corrugated-board/box manufacturing differs from
1701 in one structural respect that shapes this design: 1701's
domain (chemical/mechanical pulping, effluent discharge) carries a
distinct, separately-regulated environmental-discharge concern that
this task brief does not call for here -- corrugating/converting
lines (corrugator, printer-slotter, flexo-folder-gluer, die-cutter)
have a mechanical/equipment-safety hazard profile but no analogous
regulated-discharge axis. This build therefore implements a SINGLE
PERMANENT block on the proposal's own `:effect` (any proposal that
would directly control corrugator/converting-line equipment), not
1701's two independent permanent blocks.

This vertical has NO pre-existing `kotoba-lang/corrugated`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`corrugated.registry` (equipment/batch verification, shipment-quantity
recompute, board/container-grade validation, burst-strength (Mullen)
and edge-crush-test (ECT) plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-1701`'s `pulppaper.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:corrugated-packaging-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code "corrugated-packaging-plant" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external corrugated capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
corrugated-board/box vertical has NO pre-existing capability library
to wrap. The equipment/batch-verification / shipment-quantity / grade
/ burst-strength / edge-crush validation functions live as pure
functions in `corrugated.registry` and are re-verified independently
by `corrugated.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1701`'s `pulppaper.registry`).

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of corrugated-
board/box plant operations. It does NOT:
- Control the corrugator, printer-slotter, flexo-folder-gluer, or die-cutter directly
- Make plant-safety or quality-defect-disposition decisions (exclusive to the human plant supervisor)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority --
it is a proposal-screening and documentation layer.

### Decision 3: Safety-concern escalation -- always human sign-off

`:flag-safety-concern` (equipment hazard, quality-defect concern,
crew exposure) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" -- it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Mirroring `cloud-itonami-isic-1701`'s own structure, this vertical has
TWO entity kinds each gating a different op: `:schedule-maintenance`
independently verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "plant/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date quantity
plus the proposal's own claimed quantity would exceed the batch's own
recorded production quantity -- never taken on the advisor's
self-report.

### Decision 5: A single PERMANENT block on the proposal's own `:effect` -- no separate discharge-authorization axis

Unlike `cloud-itonami-isic-1701`, this vertical's task brief calls for
exactly ONE permanent, unconditional block: "any proposal touching
corrugator/converting-line-equipment control is a hard, permanent
block." `equipment-control-blocked-violations` implements this by
checking the proposal's own `:effect` against the closed
propose-shaped effect allowlist (`:batch/upsert`,
`:maintenance/schedule`, `:safety-concern/flag`, `:shipment/propose`)
-- any other value (e.g. a hallucinated `:corrugator/actuate`) is
blocked unconditionally. Because the deterministic mock advisor always
emits a fixed `:effect` per op, this check is not reachable via the
normal actor-graph path with the shipped advisor; it is exercised
directly against `corrugated.governor/check` in
`corrugated.governor-contract-test`'s
`equipment-control-blocked-is-held-and-permanently-blocked`, the same
way a compromised/hallucinating advisor's output would be censored.

### Decision 6: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`corrugated.governor`, mirroring `cloud-itonami-isic-1701`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct corrugator/converting-line-equipment control is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Corrugated-board/box plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation. Safety concerns are a
circuit-breaker, not a threshold.

(+) Quality-plausibility discipline is explicit: burst-strength
(Mullen) and edge-crush-test (ECT) readings are independently
range-checked, never trusted as self-reported sensor data.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation decisions remain human-controlled
via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-1702`: `clojure -M:test` green (see the
  superproject ADR and `kotoba-lang/industry` registry entry for the
  exact re-verification output, run from an independent fresh clone at
  the merge commit), `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, already-scheduled,
  invalid-grade, invalid-burst-strength, invalid-edge-crush), plus a
  direct governor-level check for equipment-control-blocked (not
  reachable via the deterministic advisor).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
