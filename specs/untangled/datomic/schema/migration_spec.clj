(ns untangled.datomic.schema.migration-spec
  (:require
    [datomic.api :as d]
    [untangled.datomic.schema :as schema]
    [untangled-spec.core :refer [specification assertions when-mocking component behavior provided]]
    [untangled.datomic.test-helpers :refer [with-db-fixture]]
    [untangled.datomic.impl.util :as util]
    [untangled.datomic.fixtures.migration-handling-mock-data :as md]
    [clojure.test :as t]
    [taoensso.timbre :as timbre]
    [io.rkn.conformity :as c]))

(t/use-fixtures
  :once #(timbre/with-merged-config
          {:ns-blacklist ["untangled.datomic.schema"]}
          (%)))

(specification "all-migrations*"
  (behavior "INTEGRATION - finds migrations that are in the correct package and ignores the template"
    (let [result (schema/all-migrations* "untangled.datomic.fixtures.migrations")
          migA (first result)
          migB (second result)]
      (assertions
        migA => {:untangled.datomic.fixtures.migrations/A {:txes [[{:item 1}]]}}
        (keys migB) => [:untangled.datomic.fixtures.migrations/B]
        (get-in migB [:untangled.datomic.fixtures.migrations/B :txes]) => [[{:item 2}]]
        (get-in migB [:untangled.datomic.fixtures.migrations/B :migrate-data]) =fn=> fn?)))

  (behavior "does not find migrations that are in other packages"
    (let [mig1 "some.migration1"
          mig2 "some.migration2"]
      (when-mocking
        (util/load-namespaces "my.crap") => ['mig1 'mig2]

        (assertions
          (schema/all-migrations* "my.crap") => '()))))

  (behavior "skips generation and complains if the 'transactions' function is missing."
    (when-mocking
      (util/namespace-name _) => "my.crap.A"
      (util/load-namespaces "my.crap") => ['my.crap.A]
      (ns-resolve _ _) => nil

      (assertions
        (schema/all-migrations* "my.crap") => '())))

  (behavior "skips the migration and reports an error if the 'transactions' function fails to return a list of lists"
    (when-mocking
      (util/namespace-name :..migration1..) => "my.crap.A"
      (util/load-namespaces "my.crap") => [:..migration1..]
      (ns-resolve :..migration1.. 'transactions) => (fn [] [{}])
      (assertions
        (schema/all-migrations* "my.crap") => '())))

  (behavior "skips the migration named 'template'"
    (when-mocking
      (util/namespace-name :..migration1..) => "my.crap.template"
      (util/load-namespaces "my.crap") => [:..migration1..]

      (assertions
        (schema/all-migrations* "my.crap") => '()))))

(specification "check-migration-conformity"
  (provided "when database conforms to migration"
    (d/db _) => :db
    (c/conforms-to? _ _) => true
    (assertions
      "retuns an empty set"
      (schema/check-migration-conformity :db md/migrations false) => #{}))

  (provided
    "when database does not conform to a migration"
    (d/db _) => :db
    (c/conforms-to? _ _) => false
    (assertions
      "tersely reports nonconforming migrations"
      (schema/check-migration-conformity :db md/migrations false) => #{:survey.migrations/survey-20151106
                                                                       :survey.migrations/survey-20151109
                                                                       :survey.migrations/survey-20151110
                                                                       :survey.migrations/survey-20151111})
    (assertions
      "verbosely reports nonconforming migrations"
      (schema/check-migration-conformity :db md/migrations true) => (set md/migrations))))

(specification "migration-status-all"
  (provided "when multiple databases conform to migrations"
    (d/connect _) =2x=> nil
    (schema/all-migrations* _) =2x=> nil
    (schema/check-migration-conformity _ _ _) =1x=> #{}
    (schema/check-migration-conformity _ _ _) =1x=> #{}
    (assertions
      "returns no migrations"
      (schema/migration-status-all {:db1 {} :db2 {}} false) => #{}))

  (provided "when multiple databases do not conform"
    (d/connect _) =2x=> nil
    (schema/all-migrations* _) =2x=> nil
    (schema/check-migration-conformity _ _ _) =1x=> #{:db1.migs/db1-20151106 :db1.migs/db1-20151107}
    (schema/check-migration-conformity _ _ _) =1x=> #{:db2.migs/db2-20151106 :db2.migs/db2-20151107}
    (assertions
      "returns all non-conforming migrations"
      (schema/migration-status-all {:db1 {} :db2 {}} false) => #{:db2.migs/db2-20151106
                                                                 :db2.migs/db2-20151107
                                                                 :db1.migs/db1-20151106
                                                                 :db1.migs/db1-20151107})))

(specification "migrate"
  (provided "when provided with data migration functions"
    (c/ensure-conforms _ _) => nil
    (c/conforms-to? _ _) =1x=> false
    (c/conforms-to? _ _) =1x=> true
    (c/conforms-to? _ _) =1x=> false
    (c/conforms-to? _ _) =1x=> true
    (schema/dump-schema _) => nil
    (d/db _) => nil

    (let [migrate-called (atom false)]
      (schema/migrate migrate-called "untangled.datomic.fixtures.migrations")
      (assertions
        "runs the data migration upon successful schema migration."
        @migrate-called => true))))

(specification "migrate-all"
  (provided "when given a configuration with multiple databases"
    (d/connect _) =2x=> nil
    (schema/migrate _ _) =2x=> nil
    (schema/run-core-schema _) =2x=> nil
    (assertions
      "runs core schema and migrations for each database"
      (schema/migrate-all md/list-dbs-config) => nil)))
