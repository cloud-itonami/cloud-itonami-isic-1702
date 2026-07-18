(ns corrugated.registry
  "Pure-function domain logic for the corrugated-board/box-manufacturing
  plant-operations coordination actor -- equipment/batch verification,
  shipment-quantity recompute, board/container-grade validation,
  burst-strength (Mullen) and edge-crush-test (ECT) plausibility
  validation, and draft maintenance-schedule/shipment-coordination
  record construction.

  This vertical has NO pre-existing `kotoba-lang/corrugated`-style
  capability library to wrap (verified: no such repo exists, mirroring
  `cloud-itonami-isic-1701`'s own pulp/paper vertical, docs/adr/0001-
  architecture.md Decision 1). The domain logic therefore lives here as
  pure functions, re-verified INDEPENDENTLY by `corrugated.governor` --
  the same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes: never trust a proposal's own
  self-reported quantity/status when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real mill-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating corrugator/
  converting-line equipment or dispatching a real freight carrier
  (this actor NEVER does either -- see README `What this actor does
  NOT do`).")

;; ----------------------------- constants -----------------------------

(def valid-board-grades
  "The closed set of corrugated-board/container-grade values a
  production-batch record may declare, spanning both intermediate
  corrugated-board grades (single-wall flute profiles, multi-wall
  board) and finished container/carton grades. Anything else is a
  fabricated/unrecognized grade -- the governor HARD-holds rather than
  let an invented grade pass through."
  #{:single-wall-a-flute :single-wall-b-flute :single-wall-c-flute
    :single-wall-e-flute :single-wall-f-flute
    :double-wall-board :triple-wall-board
    :single-face-board :containerboard :linerboard-kraft
    :corrugating-medium :regular-slotted-container :die-cut-carton})

(def burst-strength-min-kpa
  "Physical floor for a Mullen burst-strength reading (a board sample
  cannot resist negative pressure)."
  0.0)

(def burst-strength-max-kpa
  "Physical ceiling for a Mullen burst-strength (ISO 2758) reading.
  Even the heaviest triple-wall high-performance corrugated board does
  not plausibly exceed this -- a reading above it is implausible
  sensor/instrument data, not a real batch."
  5000.0)

(def edge-crush-min-kn-m
  "Physical floor for an Edge Crush Test (ECT, ISO 3037/TAPPI T 839)
  reading (a board sample cannot resist negative compressive load)."
  0.0)

(def edge-crush-max-kn-m
  "Physical ceiling for an Edge Crush Test reading. Even heavy
  multi-wall high-ECT board does not plausibly exceed this -- a
  reading above it is implausible sensor/instrument data, not a real
  batch."
  50.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its grade/quantity/quality claims have actually been
  QC-inspected, not merely logged from an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-quantity-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal: would
  `shipped-to-date-units` + `new-quantity-units` exceed `batch`'s own
  recorded `:quantity-units` (the batch's own logged production
  quantity, sheets or boxes)? Needs no proposal inspection or
  stored-verdict lookup -- its inputs are permanent fields already on
  the batch's own record, the same shape every sibling actor's own
  cost/total-matching check uses."
  [batch new-quantity-units]
  (let [capacity (:quantity-units batch)
        so-far (:shipped-quantity-units batch 0.0)]
    (and (number? capacity)
         (number? new-quantity-units)
         (> (+ (double so-far) (double new-quantity-units)) (double capacity)))))

(defn grade-valid?
  "Is `grade` one of the closed, known corrugated-board/container-grade
  values? nil/blank is treated as invalid (a production-batch patch
  must declare a real grade, not omit it silently)."
  [grade]
  (contains? valid-board-grades grade))

(defn burst-strength-valid?
  "Is `kpa` a physically plausible Mullen burst-strength reading?
  Rejects nil, non-numbers, negative values, and values beyond
  `burst-strength-max-kpa` -- a fabricated or sensor-error reading,
  never let through as a real batch fact."
  [kpa]
  (and (number? kpa)
       (>= (double kpa) burst-strength-min-kpa)
       (<= (double kpa) burst-strength-max-kpa)))

(defn edge-crush-valid?
  "Is `kn-m` a physically plausible Edge Crush Test reading? Rejects
  nil, non-numbers, negative values, and values beyond
  `edge-crush-max-kn-m` -- a fabricated or sensor-error reading, never
  let through as a real batch fact."
  [kn-m]
  (and (number? kn-m)
       (>= (double kn-m) edge-crush-min-kn-m)
       (<= (double kn-m) edge-crush-max-kn-m)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  corrugator/converting-line maintenance window against a verified,
  registered piece of equipment. Pure function -- does not actuate
  corrugator/converting-line equipment or execute any maintenance; it
  builds the RECORD a plant coordinator would keep.
  `corrugated.governor` independently re-verifies the equipment's own
  verified/registered ground truth before this is ever allowed to
  commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound corrugated-board-sheet/box shipment against a verified,
  registered production batch. Pure function -- does not dispatch any
  real freight carrier; it builds the RECORD a plant coordinator would
  keep. `corrugated.governor` independently re-verifies the shipment's
  own claimed quantity against `shipment-quantity-exceeded?`, before
  this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))

;; ─────── Downstream Cross-Actor Handoff (optional, isic-1702 -> isic-1075) ───────
;;
;; `:coordinate-shipment` proposals MAY OPTIONALLY carry a `:handoff`
;; record under the proposal's `:value` when this actor dispatches a
;; finished corrugated-case shipment to a downstream food-manufacturer
;; consumer of its packaging (e.g. cloud-itonami-isic-1075). Reuses the
;; SAME `:handoff/*` wire shape isic-1075 already uses for its own
;; downstream isic-1075<->jsic-4721 handoff -- see superproject
;; ADR-2800000800. A `:handoff` here is OPTIONAL, not required: existing
;; shipment-coordination proposals worked before this field existed and
;; keep working unchanged with no `:handoff` attached at all.
;;
;;   {:handoff/id "..."
;;    :handoff/source-actor "cloud-itonami-isic-1702"
;;    :handoff/batch-id "..."
;;    :handoff/product-type-id :regular-slotted-container
;;    :handoff/quantity-kg 80.0
;;    :handoff/dispatched-at-iso "..."}

(defn handoff-record-well-formed?
  "Positive-sense convenience predicate: does `handoff` carry every
  REQUIRED `:handoff/*` field (id/source-actor/batch-id/product-type-id/
  quantity-kg/dispatched-at-iso) with a plausible value (quantity-kg a
  positive number, the string fields non-blank)? Never validates the
  OPTIONAL cold-chain/unspsc/gtin fields."
  [handoff]
  (boolean
   (and (map? handoff)
        (seq (:handoff/id handoff))
        (seq (:handoff/source-actor handoff))
        (seq (:handoff/batch-id handoff))
        (some? (:handoff/product-type-id handoff))
        (number? (:handoff/quantity-kg handoff))
        (pos? (:handoff/quantity-kg handoff))
        (seq (:handoff/dispatched-at-iso handoff)))))
