(ns flatland.jiraph.graph-test
  (:use clojure.test flatland.jiraph.graph
        [flatland.retro.core :as retro :only [at-revision]])
  (:require ;[flatland.jiraph.stm-layer :as stm]
            [flatland.jiraph.layer :as layer]
            [flatland.jiraph.ruminate :as ruminate]
            [flatland.jiraph.layer.masai :as masai]
            [flatland.jiraph.layer.masai-sorted :as sorted]))

(defmacro actions [layer & forms]
  (let [layer-sym (gensym 'layer)]
    `(let [~layer-sym ~layer]
       (compose ~@(for [form forms]
                    `(-> ~layer-sym ~form))))))

(defn test-layer [master]
  (testing (str (class (unwrap-all master)))
    (truncate! master)
    (let [rev (memoize (fn [revision] (at-revision master revision)))
          mike-node {:age 21 :edges {"profile-2" {:rel :mom :exists true}}}
          carla-node {:age 48}]
      (txn (actions (rev 0)
                    (assoc-node "profile-1" mike-node)
                    (assoc-node "profile-2" carla-node)))
      (testing "Old revisions are untouched"
        (is (= nil (get-node (rev 0) "profile-1"))))
      (testing "Node data is written"
        (is (= mike-node (get-node (rev 1) "profile-1"))))

      (testing "Nil revision reads latest data"
        (is (= mike-node (get-node master "profile-1"))))
      (testing "Future revisions can be read"
        (is (= mike-node (get-node (rev 4) "profile-1"))))
      (testing "Basic incoming"
        (is (= #{"profile-1"}
               (get-incoming (rev 1) "profile-2")))
        (is (empty? (get-incoming (rev 1) "profile-1"))))

      (let [action-map (actions (rev 1)
                                (assoc-node "profile-3" {:edges {"profile-2" {:rel :mom
                                                                              :exists true}}})
                                (update-node "profile-3" assoc :age 18)
                                (update-in-node ["profile-1" :age] inc))]
        (testing "Writes can't be seen while queueing"
          (is (nil? (get-node (rev 1) "profile-3")))
          (is (nil? (get-node (rev 2) "profile-3")))
          (is (nil? (get-node master "profile-3"))))
        (txn action-map))
      (testing "Previous revisions still exist on unmodified nodes"
        (is (= carla-node (get-node (rev 1) "profile-2")))
        (is (= carla-node (get-node (rev 2) "profile-2"))))
      (testing "Previous revisions exist on modified nodes"
        (is (= mike-node (get-node (rev 1) "profile-1")))
        (is (= (update-in mike-node [:age] inc)
               (get-node (rev 2) "profile-1")
               (get-node master "profile-1"))))

      (testing "Updates see previous writes"
        (is (= {:age 18 :edges {"profile-2" {:rel :mom :exists true}}}
               (get-node (rev 2) "profile-3"))))
      (testing "Incoming is revisioned"
        (is (= #{"profile-1"} (get-incoming (rev 1) "profile-2")))
        (is (= #{"profile-1" "profile-3"} (get-incoming (rev 2) "profile-2"))))

      (testing "Changelog support"
        (testing "get-revisions"
          (is (= #{1 2} (set (get-revisions (rev 2) "profile-1"))))
          (testing "Don't know about future revisions"
            (is (= #{1} (set (get-revisions (rev 1) "profile-1")))))
          (is (= #{1} (set (get-revisions master "profile-2")))))
        (comment We decided not to support/implement this yet, and it's not a crucial feature.
                 Leaving tests in so that it's clear how layers *should* behave.
                 (testing "get-changed-ids"
                   (is (= #{"profile-1" "profile-2"}
                          (set (layer/get-changed-ids master 1))))
                   (is (= #{"profile-1" "profile-3"}
                          (set (layer/get-changed-ids (rev 2) 2))))))
        (testing "max-revision"
          (is (= 2 (retro/max-revision master)))
          (is (= 2 (retro/max-revision (rev 1))))))

      (testing "Can't rewrite history"
        (txn (actions (rev 0)
                      (assoc-node "profile-4" {:age 72})))
        (doseq [r (range 5)]
          (is (nil? (get-node (rev r) "profile-4")))))

      (testing "Transaction safety"
        (testing "Can't mutate active layer while building a transaction"
          (is (thrown? Exception
                       (txn
                         (do (assoc-node! (rev 3) "profile-5" {:age 2})
                             {}))))))

      (testing "Reporting of revision views"
        (is (= 2 (retro/current-revision (rev 2))))
        (is (nil? (retro/current-revision master))))

      (testing "dissoc at top level"
        (let [node {:age 21 :edges {"profile-7" {:rel :mom :exists true}}}]
          (txn (assoc-node  (rev 4) "profile-6" node))
          (txn (dissoc-node (rev 5) "profile-6"))
          (is (= node (get-node (rev 5) "profile-6")))
          (is (nil?   (get-node (rev 6) "profile-6"))))))))

(deftest layer-impls
  ;; add more layers as they're implemented
  (doseq [layer-fn [#(sorted/make-temp :layout-fn (-> (constantly [{:pattern [:edges :*]},
                                                                   {:pattern []}])
                                                      (sorted/wrap-default-formats)
                                                      (sorted/wrap-revisioned)))
                    #(masai/make-temp)]]
    (let [layer (ruminate/incoming (layer-fn) (layer-fn))]
      (layer/open layer)
      (try
        (test-layer layer)
        (finally (layer/close layer))))))
