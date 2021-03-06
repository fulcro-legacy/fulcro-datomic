(ns fulcro.datomic.core-spec
  (:require [fulcro.datomic.core :as core]
            [fulcro.datomic.schema :as schema]
            [fulcro.datomic.test-helpers :as test]
            [fulcro.datomic.protocols :as ud]
            [io.rkn.conformity :as c]
            [fulcro-spec.core :refer [when-mocking specification provided behavior assertions]]
            [fulcro.datomic.fixtures.migration-handling-mock-data :as md]
            [fulcro.datomic.impl.util :as util]
            [clojure.test :as t]
            [taoensso.timbre :as timbre]))

(t/use-fixtures
  :once #(timbre/with-merged-config
           {:ns-blacklist ["fulcro.datomic.core"]}
           (%)))

(specification "The query function"
  (test/with-db-fixture fixture
    (let [username (core/query '[:find ?name . :in $ ?id :where
                                 [?a :user/username ?name]
                                 [?a :user/address ?id]]
                     fixture
                     :datomic.id/address)
          zipcode (core/query '[:find ?zip . :in $ ?status :where
                                [?a :address/zipcode ?zip]
                                [?e :user/address ?a]
                                [?e :user/status ?status]]
                    fixture
                    :user.status/pending)]

      (assertions
        "If bound variable is a seeded-id, resolves it to a real datomic id."
        username => "fulcro"

        "If bound variable is not a seeded-id, leaves as is."
        zipcode => 97702))

    :migrations "sample-migrations.migrations.test"
    :seed-fn (fn [conn] (test/link-and-load-seed-data
                          conn [{:db/id           :datomic.id/address
                                 :address/zipcode 97702}
                                {:db/id         :datomic.id/user
                                 :user/username "fulcro"
                                 :user/address  :datomic.id/address
                                 :user/status   :user.status/pending}]))))

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
