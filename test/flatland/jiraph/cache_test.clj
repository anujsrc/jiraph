(ns flatland.jiraph.cache-test
  (:use clojure.test flatland.jiraph.core
        [flatland.jiraph.walk :only
         [defwalk path paths *parallel-follow* intersection enable-walk-cache! reset-walk-cache!]]
        [flatland.jiraph.walk.predicates :only [at-limit]]
        [flatland.jiraph.formats.protobuf :only [protobuf-format]]
        [fogus.unk :only [memo-lru snapshot]])
  (:require [flatland.jiraph.masai-layer :as bal]
            [flatland.jiraph.stm-layer :as stm]
            [flatland.masai.tokyo :as tokyo])
  (:import (flatland.jiraph Test$Node)))

(def test-graph
  {:foo (bal/make (tokyo/make {:path "/tmp/jiraph-cached-walk-test-foo" :create true})
                  :format-fn (protobuf-format Test$Node))})

(defwalk full-walk
  :add?      true
  :traverse? true
  :cache     true)

(deftest test-lru
  (enable-walk-cache! memo-lru 5)
  (with-graph test-graph
    (truncate!)

    (assoc-node! :foo "1" {:edges {"2" {:a "foo"} "3" {:a "bar"}}})
    (assoc-node! :foo "2" {:edges {"5" {:a "foo"} "6" {:a "bar"}}})
    (assoc-node! :foo "4" {:edges {"7" {:a "foo"} "8" {:a "bar"}}})
    (assoc-node! :foo "8" {:edges {"8" {:a "foo"} "9" {:a "bar"}}}))

  (let [foci ["1" "2" "4" "8"]]
    (doseq [focus foci]
      (dotimes [n 2]
        (full-walk focus)))
    (is (= (sort foci)
           (sort (map first (keys (snapshot flatland.jiraph.walk/cached-walk))))))
    (reset-walk-cache!)
    (is (= 0 (count(keys (snapshot flatland.jiraph.walk/cached-walk)))))))