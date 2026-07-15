(ns corrugated.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean plant through
  intake -> maintenance scheduling (escalate/approve) -> safety-concern
  flag (escalate/approve) -> shipment coordination (escalate/approve),
  then shows HARD-hold scenarios: a mis-wired request whose own
  `:effect` is not `:propose`, an unrecognized op, maintenance
  scheduled against an UNVERIFIED/unregistered equipment unit, a
  shipment coordinated against an UNVERIFIED/unregistered batch, a
  shipment proposal that would exceed the batch's own logged
  production quantity, a proposal that tries to directly CONTROL
  corrugator/converting-line equipment (permanently blocked, no
  override), a double-schedule of the same maintenance window, a
  production-batch patch with a fabricated grade, a production-batch
  patch with an implausible burst-strength reading, and a
  production-batch patch with an implausible edge-crush-test reading.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [corrugated.store :as store]
            [corrugated.governor :as governor]
            [corrugated.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-production-batch batch-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:grade :single-wall-c-flute :last-assessed "2026-07-15"}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 on equip-001 (verified, registered, corrugator -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "equip-001" :maintenance-type :corrugator-inspection
                               :scheduled-date "2026-08-01"}}
                      coordinator)]
      (println r)
      (println "-- human plant supervisor approves --")
      (println (approve! actor "t2")))

    (println "== flag-safety-concern concern-1 on equip-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "equip-001" :severity :moderate
                               :description "コルゲータ主軸付近で異音"}}
                      coordinator)]
      (println r)
      (println "-- human plant supervisor approves --")
      (println (approve! actor "t3")))

    (println "== coordinate-shipment ship-1 on batch-001 (verified, registered, within quantity -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :coordinate-shipment :effect :propose :subject "ship-1"
                       :value {:batch-id "batch-001" :quantity-units 500.0
                               :destination "buyer-box-north"}}
                      coordinator)]
      (println r)
      (println "-- human shipping approver approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-production-batch with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-production-batch :effect :direct-write :subject "batch-001"
                        :patch {:grade :single-wall-c-flute}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :actuate-corrugator :effect :propose :subject "batch-001"}
                       coordinator))

    (println "== schedule-maintenance mnt-2 on equip-002 (UNVERIFIED/unregistered die-cutter -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "equip-002" :maintenance-type :die-cutter-service
                                :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== coordinate-shipment ship-2 on batch-003 (UNVERIFIED/unregistered batch -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :quantity-units 10.0
                                :destination "buyer-box-south"}}
                       coordinator))

    (println "== coordinate-shipment ship-3 on batch-002 (100 units would exceed quantity 800 vs shipped 750 -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :quantity-units 100.0
                                :destination "buyer-box-east"}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t10"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "equip-001" :maintenance-type :corrugator-inspection
                                :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== log-production-batch batch-001 with a fabricated grade -> HARD hold ==")
    (println (exec-op actor "t11"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:grade :premium-plus-select}}
                       coordinator))

    (println "== log-production-batch batch-001 with an implausible burst-strength reading -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:burst-strength-kpa 50000.0}}
                       coordinator))

    (println "== log-production-batch batch-001 with an implausible edge-crush-test reading -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:edge-crush-kn-m 500.0}}
                       coordinator))

    (println "\n-- direct governor-level check (not reachable via the deterministic advisor, which never emits a non-allowlisted :effect) --\n")
    (println "== a hand-built proposal whose own :effect is a fabricated direct-actuation effect -> HARD hold, PERMANENT ==")
    (println (governor/check
              {:op :schedule-maintenance :effect :propose :subject "mnt-4"}
              coordinator
              {:effect :corrugator/actuate :value {:equipment-id "equip-001"} :confidence 0.95}
              db))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft maintenance records ==")
    (doseq [r (store/maintenance-history db)] (println r))

    (println "\n== draft shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))))
