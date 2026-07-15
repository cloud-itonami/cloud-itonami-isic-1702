(ns corrugated.governor
  "Corrugated Packaging Plant Operations Governor -- the independent
  compliance layer that earns the CorrugatedPackagingAdvisor the right
  to commit. The advisor has no notion of whether a piece of equipment
  it wants to schedule maintenance against has actually been
  inspected/registered, whether a batch it wants to coordinate a
  shipment against has actually been QC-verified/registered, whether a
  maintenance proposal secretly tries to directly CONTROL corrugator/
  converting-line equipment (rather than merely draft-schedule a
  maintenance window), whether a shipment proposal's own claimed
  quantity would blow through the batch's own logged production
  quantity, or when an act stops being a coordination proposal and
  becomes direct corrugator/converting-line-equipment control, so this
  MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  `:itonami.blueprint/governor` is `:corrugated-packaging-plant-
  operations-governor` (see docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:corrugator/
                                       actuate` or `:converting-line/
                                       actuate`) is the 'direct
                                       corrugator/converting-line-
                                       equipment control' scope
                                       violation this actor must NEVER
                                       perform -- HARD, PERMANENT,
                                       unconditional.
    4. Equipment not verified/
       registered                  -- for `:schedule-maintenance`,
                                       INDEPENDENTLY verify the
                                       referenced equipment's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`corrugated.registry/equipment-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status. Grounded in this
                                       blueprint's own HARD invariant
                                       ('plant/batch record must be
                                       independently verified/
                                       registered before any action'):
                                       maintenance must never be
                                       scheduled against equipment
                                       whose own conditions have not
                                       actually been inspected or
                                       whose registration is not
                                       actually on file.
    5. Already scheduled           -- for `:schedule-maintenance`,
                                       refuses to schedule the SAME
                                       maintenance record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    6. Batch not verified/
       registered                  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY verify the
                                       referenced batch's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`corrugated.registry/batch-
                                       ready?`) -- never trust the
                                       advisor's own rationale. Also
                                       part of the 'plant/batch record'
                                       HARD invariant: a batch's own
                                       verified/registered status is as
                                       much a ground-truth fact as an
                                       equipment unit's own.
    7. Shipment quantity exceeded  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY recompute whether
                                       the batch's own recorded
                                       `:shipped-quantity-units` plus
                                       the proposal's own claimed
                                       `:quantity-units` would exceed
                                       the batch's own recorded
                                       `:quantity-units`
                                       (`corrugated.registry/shipment-
                                       quantity-exceeded?`) -- ground
                                       truth from the batch's own
                                       permanent fields, never a
                                       self-reported quantity claim.
    8. Invalid grade               -- for `:log-production-batch`, if
                                       the patch declares a `:grade`
                                       outside the closed known set
                                       (`corrugated.registry/grade-
                                       valid?`), the batch record is
                                       rejected rather than let a
                                       fabricated grade through.
    9. Invalid burst strength      -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:burst-strength-kpa` that is
                                       not a physically plausible
                                       Mullen burst-strength reading
                                       (`corrugated.registry/burst-
                                       strength-valid?`), the batch
                                       record is rejected rather than
                                       let fabricated/sensor-error
                                       data through.
   10. Invalid edge crush         -- for `:log-production-batch`, if
                                       the patch declares an
                                       `:edge-crush-kn-m` that is not a
                                       physically plausible Edge Crush
                                       Test reading
                                       (`corrugated.registry/edge-
                                       crush-valid?`), the batch record
                                       is rejected rather than let
                                       fabricated/sensor-error data
                                       through.
   11. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       plant supervisor. SOFT: the
                                       human may approve."
  (:require [corrugated.registry :as registry]
            [corrugated.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct corrugator/
  converting-line-equipment-control effect."
  #{:batch/upsert :maintenance/schedule
    :safety-concern/flag :shipment/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns are the one op in this domain that always demands
  human eyes regardless of confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct corrugator/printer-slotter/flexo-folder-gluer/
  die-cutter equipment control, a fabricated actuation effect) is this
  actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") はコルゲータ・製函コンバーティングライン設備の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- equipment-not-verified-violations
  "For `:schedule-maintenance`, INDEPENDENTLY verify the referenced
  equipment exists and is both `:verified?` AND `:registered?` --
  never trust the advisor's own report. This is the HARD invariant
  ('plant/batch record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-maintenance)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での保守作業予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-maintenance`, refuses to schedule the SAME
  maintenance record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-maintenance)
    (when (store/maintenance-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- batch-not-verified-violations
  "For `:coordinate-shipment`, INDEPENDENTLY verify the referenced
  batch exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report. Also part of the 'plant/batch
  record must be independently verified/registered before any action'
  HARD invariant."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みバッチ記録が無い状態での出荷調整提案")}]))))

(defn- shipment-quantity-exceeded-violations
  "For `:coordinate-shipment`, INDEPENDENTLY recompute whether the
  batch's own recorded shipped-to-date quantity plus the proposal's
  own claimed quantity would exceed the batch's own recorded
  `:quantity-units` -- ground truth from the batch's own permanent
  fields, never a self-reported quantity claim."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [{:keys [batch-id quantity-units]} (:value proposal)
          b (and batch-id (store/batch st batch-id))]
      (when (and b (registry/shipment-quantity-exceeded? b quantity-units))
        [{:rule :shipment-quantity-exceeded
          :detail (str batch-id " の記録済み生産量(" (:quantity-units b)
                       "単位)を、既存出荷実績(" (:shipped-quantity-units b 0.0)
                       "単位)+今回申請(" quantity-units "単位)が超過")}]))))

(defn- invalid-grade-violations
  "For `:log-production-batch`, if the patch declares a `:grade`
  outside the closed known set, reject rather than let a fabricated
  grade through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [grade (:grade (:value proposal))]
      (when (and (some? grade) (not (registry/grade-valid? grade)))
        [{:rule :invalid-grade
          :detail (str grade " は既知の grade 値ではない")}]))))

(defn- invalid-burst-strength-violations
  "For `:log-production-batch`, if the patch declares a
  `:burst-strength-kpa` that is not a physically plausible Mullen
  burst-strength reading, reject rather than let fabricated/
  sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [kpa (:burst-strength-kpa (:value proposal))]
      (when (and (some? kpa) (not (registry/burst-strength-valid? kpa)))
        [{:rule :invalid-burst-strength
          :detail (str kpa "kPa は物理的に妥当な破裂強度(Mullen)の範囲外")}]))))

(defn- invalid-edge-crush-violations
  "For `:log-production-batch`, if the patch declares an
  `:edge-crush-kn-m` that is not a physically plausible Edge Crush
  Test reading, reject rather than let fabricated/sensor-error data
  through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [kn-m (:edge-crush-kn-m (:value proposal))]
      (when (and (some? kn-m) (not (registry/edge-crush-valid? kn-m)))
        [{:rule :invalid-edge-crush
          :detail (str kn-m "kN/m は物理的に妥当な圧縮強さ(ECT)の範囲外")}]))))

(defn check
  "Censors a CorrugatedPackagingAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (equipment-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (batch-not-verified-violations request proposal st)
                           (shipment-quantity-exceeded-violations request proposal st)
                           (invalid-grade-violations request proposal)
                           (invalid-burst-strength-violations request proposal)
                           (invalid-edge-crush-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
