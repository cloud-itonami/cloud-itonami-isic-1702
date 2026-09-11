(ns corrugated.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT control corrugator/converting-line
  equipment directly') implemented faithfully. The single invariant
  under test:

    CorrugatedPackagingAdvisor never schedules maintenance, flags a
    safety concern, or coordinates a shipment the Corrugated Packaging
    Plant Operations Governor would reject; `:schedule-maintenance`/
    `:flag-safety-concern`/`:coordinate-shipment` NEVER auto-commit at
    any phase; `:log-production-batch` (no physical risk) MAY
    auto-commit when clean; and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [corrugated.store :as store]
            [corrugated.governor :as governor]
            [corrugated.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-production-batch-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-production-batch :effect :propose :subject "batch-001"
                   :patch {:grade :single-wall-c-flute}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :single-wall-c-flute (:grade (store/batch db "batch-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-maintenance-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                     :value {:equipment-id "equip-001" :maintenance-type :corrugator-inspection
                             :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/maintenance db "mnt-1"))))
        (is (= 1 (count (store/maintenance-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-production-batch :effect :direct-write :subject "batch-001"
                     :patch {:grade :single-wall-c-flute}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :actuate-corrugator :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest equipment-not-verified-is-held-and-unoverridable
  (testing "scheduling against an unverified/unregistered equipment unit -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                     :value {:equipment-id "equip-002" :maintenance-type :die-cutter-service
                             :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:equipment-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-history db))))))

(deftest batch-not-verified-is-held-and-unoverridable
  (testing "coordinating a shipment against an unverified/unregistered batch -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :coordinate-shipment :effect :propose :subject "ship-2"
                     :value {:batch-id "batch-003" :quantity-units 10.0
                             :destination "buyer-box-south"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:batch-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest shipment-quantity-exceeded-is-held-and-unoverridable
  (testing "a shipment proposal whose quantity would exceed the batch's own logged quantity -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :coordinate-shipment :effect :propose :subject "ship-3"
                     :value {:batch-id "batch-002" :quantity-units 100.0
                             :destination "buyer-box-east"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:shipment-quantity-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest handoff-malformed-is-held-and-unoverridable
  (testing "a shipment proposal whose OPTIONAL :handoff record is present but malformed -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7b"
                    {:op :coordinate-shipment :effect :propose :subject "ship-4"
                     :value {:batch-id "batch-001" :quantity-units 10.0
                             :destination "buyer-box-north"
                             :handoff {:handoff/id "h-1"
                                       :handoff/source-actor "cloud-itonami-isic-1702"
                                       :handoff/batch-id "batch-001"
                                       :handoff/product-type-id :single-wall-c-flute
                                       :handoff/dispatched-at-iso "2026-07-17T00:00:00Z"}}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:handoff-malformed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest handoff-well-formed-does-not-add-a-hold-reason
  (testing "a shipment proposal with a WELL-FORMED optional :handoff still escalates (coordinate-shipment always needs approval, same as the handoff-less clean case) rather than being HELD for :handoff-malformed"
    (let [[db actor] (fresh)
          res (exec-op actor "t7c"
                    {:op :coordinate-shipment :effect :propose :subject "ship-5"
                     :value {:batch-id "batch-001" :quantity-units 10.0
                             :destination "buyer-box-north"
                             :handoff {:handoff/id "h-2"
                                       :handoff/source-actor "cloud-itonami-isic-1702"
                                       :handoff/batch-id "batch-001"
                                       :handoff/product-type-id :single-wall-c-flute
                                       :handoff/quantity-kg 40.0
                                       :handoff/dispatched-at-iso "2026-07-17T00:00:00Z"}}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t7c")]
        (is (= :commit (get-in r2 [:state :disposition])))))))

(deftest schedule-maintenance-double-schedule-is-held
  (testing "scheduling the SAME maintenance record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t8a" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                  :value {:equipment-id "equip-001" :maintenance-type :corrugator-inspection
                                          :scheduled-date "2026-08-01"}} coordinator)
          _ (approve! actor "t8a")
          res (exec-op actor "t8" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                   :value {:equipment-id "equip-001" :maintenance-type :corrugator-inspection
                                           :scheduled-date "2026-08-01"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/maintenance-history db))) "still only the one earlier schedule"))))

(deftest invalid-grade-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t9" {:op :log-production-batch :effect :propose :subject "batch-001"
                                 :patch {:grade :premium-plus-select}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-grade} (-> (store/ledger db) last :basis)))
    (is (not= :premium-plus-select (:grade (store/batch db "batch-001"))) "fabricated grade never lands in the SSoT")))

(deftest invalid-burst-strength-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-production-batch :effect :propose :subject "batch-001"
                                  :patch {:burst-strength-kpa 50000.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-burst-strength} (-> (store/ledger db) last :basis)))
    (is (not= 50000.0 (:burst-strength-kpa (store/batch db "batch-001"))) "fabricated burst-strength never lands in the SSoT")))

(deftest invalid-edge-crush-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :log-production-batch :effect :propose :subject "batch-001"
                                  :patch {:edge-crush-kn-m 500.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-edge-crush} (-> (store/ledger db) last :basis)))
    (is (not= 500.0 (:edge-crush-kn-m (store/batch db "batch-001"))) "fabricated edge-crush never lands in the SSoT")))

(deftest safety-concern-always-escalates-even-high-confidence
  (testing "flag-safety-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                    :value {:equipment-id "equip-001" :severity :moderate
                                            :description "corrugator main-shaft abnormal noise"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t12")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/safety-concerns db))))))))

(deftest safety-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t13" {:op :flag-safety-concern :effect :propose :subject "concern-2"
                                :value {:equipment-id "equip-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t13")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/safety-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest coordinate-shipment-always-needs-approval
  (testing "a CLEAN shipment coordination is never auto-eligible -- always escalates, even below any quantity threshold"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                    :value {:batch-id "batch-001" :quantity-units 500.0
                                            :destination "buyer-box-north"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t14")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/shipment-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-production-batch :effect :propose :subject "batch-001"
                          :patch {:grade :single-wall-c-flute}} coordinator)
      (exec-op actor "b" {:op :log-production-batch :effect :propose :subject "batch-001"
                          :patch {:grade :fabricated-grade}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ----------------------------- direct governor-level check -----------------------------
;; The equipment-control-blocked permanent invariant is NOT reachable
;; via the deterministic mock advisor (it always emits a fixed
;; :effect per op) -- exercised here directly against
;; `corrugated.governor/check`, the same way a compromised/
;; hallucinating advisor's output would be censored.

(deftest equipment-control-blocked-is-held-and-permanently-blocked
  (testing "a proposal whose own :effect is outside the closed propose-shaped allowlist -> HOLD, PERMANENT, regardless of confidence"
    (let [[db _actor] (fresh)
          verdict (governor/check
                   {:op :schedule-maintenance :effect :propose :subject "mnt-4"}
                   coordinator
                   {:effect :corrugator/actuate :value {:equipment-id "equip-001"} :confidence 0.95}
                   db)]
      (is (true? (:hard? verdict)))
      (is (false? (:ok? verdict)))
      (is (some #{:equipment-control-blocked} (mapv :rule (:violations verdict)))))))
