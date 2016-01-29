(ns untangled.datomic.core-spec
  (:require [untangled.datomic.core :as core]
            [untangled.datomic.schema :as schema]
            [io.rkn.conformity :as c]
            [untangled-spec.core :refer [when-mocking specification provided behavior assertions]]
            [untangled.datomic.fixtures.migration-handling-mock-data :as md]
            [untangled.datomic.impl.util :as util]))

(specification "main-handler"
  (provided "when passed the --migrate option with the 'all' keyword"
    (util/single-arg _) => '([:migrate all])
    (schema/migrate-all _) =1x=> nil
    (behavior "calls migrate-all"
      (assertions
        (core/main-handler md/list-dbs-config ["--migrate" "all"]) => nil)))

  (provided "when passed the --migrate option with a specific database"
    (util/single-arg _) => '([:migrate s])
    (schema/migrate-all _) =1x=> nil
    (behavior "calls migrate-all"
      (assertions
        (core/main-handler {} ["--migrate" "s"]) => nil)))

  (when-mocking
    ;; Verification that mock was called with true is the test here.
    (schema/migration-status-all _ true) =1x=> #{}
    (behavior "passes verbose along with the main option"
      (assertions
        (core/main-handler {} ["-v" "-s" "all"]) => nil)))

  (assertions
    "lists databases available in the config"
    (core/main-handler md/list-dbs-config ["-l"]) => ["survey" "some-other-db"]))
