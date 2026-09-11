(ns corrugated.registry-test
  (:require [clojure.test :refer [deftest is]]
            [corrugated.registry :as r]))

;; ----------------------------- equipment-verified? / equipment-registered? / equipment-ready? -----------------------------

(deftest equipment-is-verified-when-flagged
  (is (true? (r/equipment-verified? {:id "e1" :verified? true}))))

(deftest equipment-is-not-verified-when-false-or-missing
  (is (false? (r/equipment-verified? {:id "e1" :verified? false})))
  (is (false? (r/equipment-verified? {:id "e1"}))))

(deftest equipment-is-registered-when-flagged
  (is (true? (r/equipment-registered? {:registered? true}))))

(deftest equipment-is-not-registered-when-false-or-missing
  (is (false? (r/equipment-registered? {:registered? false})))
  (is (false? (r/equipment-registered? {}))))

(deftest equipment-ready-requires-both
  (is (true? (r/equipment-ready? {:verified? true :registered? true})))
  (is (false? (r/equipment-ready? {:verified? true :registered? false})))
  (is (false? (r/equipment-ready? {:verified? false :registered? true})))
  (is (false? (r/equipment-ready? {}))))

;; ----------------------------- batch-verified? / batch-registered? / batch-ready? -----------------------------

(deftest batch-is-verified-when-flagged
  (is (true? (r/batch-verified? {:id "b1" :verified? true}))))

(deftest batch-is-not-verified-when-false-or-missing
  (is (false? (r/batch-verified? {:id "b1" :verified? false})))
  (is (false? (r/batch-verified? {:id "b1"}))))

(deftest batch-is-registered-when-flagged
  (is (true? (r/batch-registered? {:registered? true}))))

(deftest batch-is-not-registered-when-false-or-missing
  (is (false? (r/batch-registered? {:registered? false})))
  (is (false? (r/batch-registered? {}))))

(deftest batch-ready-requires-both
  (is (true? (r/batch-ready? {:verified? true :registered? true})))
  (is (false? (r/batch-ready? {:verified? true :registered? false})))
  (is (false? (r/batch-ready? {:verified? false :registered? true})))
  (is (false? (r/batch-ready? {}))))

;; ----------------------------- shipment-quantity-exceeded? -----------------------------

(deftest small-shipment-within-quantity-does-not-exceed
  (is (false? (r/shipment-quantity-exceeded?
               {:quantity-units 5000.0 :shipped-quantity-units 1000.0} 500.0))))

(deftest shipment-that-pushes-past-quantity-exceeds
  (is (true? (r/shipment-quantity-exceeded?
              {:quantity-units 800.0 :shipped-quantity-units 750.0} 100.0))))

(deftest shipment-exactly-at-quantity-does-not-exceed
  (is (false? (r/shipment-quantity-exceeded?
               {:quantity-units 800.0 :shipped-quantity-units 750.0} 50.0))
      "exactly at quantity is not over, only strictly beyond"))

(deftest missing-quantity-is-not-flagged-exceeded
  (is (false? (r/shipment-quantity-exceeded? {} 100.0)))
  (is (false? (r/shipment-quantity-exceeded? {:quantity-units 800.0} nil))))

;; ----------------------------- grade-valid? -----------------------------

(deftest known-grades-are-valid
  (doseq [g [:single-wall-a-flute :single-wall-b-flute :single-wall-c-flute
             :single-wall-e-flute :single-wall-f-flute
             :double-wall-board :triple-wall-board
             :single-face-board :containerboard :linerboard-kraft
             :corrugating-medium :regular-slotted-container :die-cut-carton]]
    (is (r/grade-valid? g))))

(deftest fabricated-grade-is-invalid
  (is (not (r/grade-valid? :premium-plus-select)))
  (is (not (r/grade-valid? nil))))

;; ----------------------------- burst-strength-valid? -----------------------------

(deftest typical-burst-strength-is-valid
  (is (r/burst-strength-valid? 700.0))
  (is (r/burst-strength-valid? 0.0))
  (is (r/burst-strength-valid? 900.0))
  (is (r/burst-strength-valid? 5000.0)))

(deftest negative-burst-strength-is-invalid
  (is (not (r/burst-strength-valid? -1.0))))

(deftest excessive-burst-strength-is-invalid
  (is (not (r/burst-strength-valid? 50000.0)))
  (is (not (r/burst-strength-valid? 5000.01))))

(deftest non-numeric-or-missing-burst-strength-is-invalid
  (is (not (r/burst-strength-valid? nil)))
  (is (not (r/burst-strength-valid? "700.0"))))

;; ----------------------------- edge-crush-valid? -----------------------------

(deftest typical-edge-crush-is-valid
  (is (r/edge-crush-valid? 6.5))
  (is (r/edge-crush-valid? 0.0))
  (is (r/edge-crush-valid? 8.0))
  (is (r/edge-crush-valid? 50.0)))

(deftest negative-edge-crush-is-invalid
  (is (not (r/edge-crush-valid? -1.0))))

(deftest excessive-edge-crush-is-invalid
  (is (not (r/edge-crush-valid? 500.0)))
  (is (not (r/edge-crush-valid? 50.01))))

(deftest non-numeric-or-missing-edge-crush-is-invalid
  (is (not (r/edge-crush-valid? nil)))
  (is (not (r/edge-crush-valid? "6.5"))))

;; ----------------------------- register-maintenance -----------------------------

(deftest maintenance-is-a-draft-not-a-real-actuation
  (let [result (r/register-maintenance "mnt-1" "equip-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest maintenance-assigns-maintenance-number
  (let [result (r/register-maintenance "mnt-1" "equip-001" 7)]
    (is (= (get result "maintenance_number") "MNT-000007"))
    (is (= (get-in result ["record" "maintenance_id"]) "mnt-1"))
    (is (= (get-in result ["record" "equipment_id"]) "equip-001"))
    (is (= (get-in result ["record" "kind"]) "maintenance-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest maintenance-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "" "equip-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "equip-001" -1))))

;; ----------------------------- register-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-dispatch
  (let [result (r/register-shipment "ship-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-shipment "ship-1" 7)]
    (is (= (get result "shipment_number") "SHP-000007"))
    (is (= (get-in result ["record" "shipment_id"]) "ship-1"))
    (is (= (get-in result ["record" "kind"]) "shipment-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "ship-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-maintenance "mnt-1" "equip-001" 0)
        hist (r/append [] c1)
        c2 (r/register-maintenance "mnt-2" "equip-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "MNT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "MNT-000001" (get-in hist2 [1 "record_id"])))))

;; ─────── Downstream Cross-Actor Handoff (optional, isic-1702 -> isic-1075) ───────

(def ^:private well-formed-handoff
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-isic-1702"
   :handoff/batch-id "batch-001"
   :handoff/product-type-id :regular-slotted-container
   :handoff/quantity-kg 80.0
   :handoff/dispatched-at-iso "2026-07-17T00:00:00Z"})

(deftest handoff-record-well-formed-test
  (is (true? (r/handoff-record-well-formed? well-formed-handoff)))
  (is (false? (r/handoff-record-well-formed? (dissoc well-formed-handoff :handoff/quantity-kg))))
  (is (false? (r/handoff-record-well-formed? (assoc well-formed-handoff :handoff/quantity-kg 0))))
  (is (false? (r/handoff-record-well-formed? nil))))
