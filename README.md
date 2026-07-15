# cloud-itonami-isic-1702: Manufacture of corrugated paper and paperboard and of containers of paper and paperboard

Open Business Blueprint for **ISIC Rev.5 1702**: manufacture of corrugated paper and paperboard and of containers of paper and paperboard — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office corrugated-board/box **plant operations**: production-batch data logging (board/container grade, quantity, burst-strength/edge-crush-test quality data), corrugator/converting-line (printer-slotter, flexo-folder-gluer, die-cutter) maintenance scheduling, safety-concern flagging, and outbound corrugated-board-sheet/box shipment coordination.

This repository designs a forkable OSS business for corrugated-board/box
plant operations: run by a qualified operator so a plant keeps its own
operating records instead of renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — corrugating/converting batch, output-quality (burst strength, edge crush test) data logging (administrative, not an operational decision)
- `:schedule-maintenance` — corrugator/converting-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/quality-defect concern (always escalates)
- `:coordinate-shipment` — outbound box/sheet shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY** — this actor coordinates back-office
records for a plant with heavy converting-line machinery (corrugators,
printer-slotters, flexo-folder-gluers, die-cutters):

- Does NOT control the corrugator, printer-slotter, flexo-folder-gluer, or die-cutter directly
- Does NOT make plant-safety or quality-defect-disposition decisions (that's the plant supervisor's exclusive human authority)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`corrugated.operation/build`, a langgraph-clj StateGraph):
1. **`corrugated.advisor`** (sealed intelligence node, `CorrugatedPackagingAdvisor`): proposes decisions only, never commits
2. **`corrugated.governor`** (independent, `Corrugated Packaging Plant Operations Governor`): validates against domain rules, re-derived from `corrugated.registry`'s pure functions and `corrugated.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects — any proposal touching corrugator/converting-line-equipment control is a hard, PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:grade` value on a production-batch patch
     - No physically implausible `:burst-strength-kpa` (Mullen burst test) value on a production-batch patch
     - No physically implausible `:edge-crush-kn-m` (Edge Crush Test) value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`corrugated.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`corrugated.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
