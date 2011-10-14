(ns jiraph.new-graph-test
  (:use clojure.test jiraph.graph
        [retro.core :as retro :only [dotxn at-revision]])
  (:require [jiraph.stm-layer :as stm]
            [jiraph.layer :as layer]))

(deftest test-everything
  (let [master (stm/make)
        rev (vec (for [r (range 5)]
                   (at-revision master r)))
        mike-node {:age 21 :edges {"carla" {:rel :mom}}}]
    (dotxn (rev 0)
      (-> (rev 0)
          (assoc-node "mike" mike-node)
          (assoc-node "carla" {:age 48})))
    (testing "Old revisions are untouched"
      (is (= nil (get-node (rev 0) "mike"))))
    (testing "Node data is written"
      (is (= mike-node (get-node (rev 1) "mike"))))
    (testing "Future revisions can be read"
      (is (= mike-node (get-node (rev 4) "mike"))))
    (testing "Basic incoming"
      (is (= #{"mike"}
             (get-incoming (rev 1) "carla")))
      (is (empty? (get-incoming (rev 1) "mike"))))

    (dotxn (rev 1)
      (let [actions (-> (rev 1)
                        (assoc-node "charles" {:edges {"carla" {:rel :mom}}})
                        (update-node "charles" assoc :age 18)
                        (update-in-node ["mike" :age] inc))]
        (testing "Writes can't be seen while queueing"
          (is (nil? (get-node actions "charles"))))
        actions))
    (testing "Updates see previous writes"
      (is (= {:age 18 :edges {"carla" {:rel :mom}}}
             (get-node (rev 2) "charles"))))
    (testing "Incoming is revisioned"
      (is (= #{"mike"} (get-incoming (rev 1) "carla")))
      (is (= #{"mike" "charles"} (get-incoming (rev 2) "carla"))))

    (testing "Changelog support"
      (testing "get-revisions"
        (is (= #{1 2} (set (get-revisions (rev 2) "mike"))))
        (testing "Don't know about future revisions"
          (is (= #{1} (set (get-revisions (rev 1) "mike")))))
        (is (= #{1} (set (get-revisions master "carla")))))
      (testing "get-changed-ids"
        (is (= #{"mike" "carla"}
               (set (layer/get-changed-ids master 1))))
        (is (= #{"mike" "charles"}
               (set (layer/get-changed-ids (rev 2) 2)))))
      (testing "max-revision"
        (is (= 2 (layer/max-revision master)))))))