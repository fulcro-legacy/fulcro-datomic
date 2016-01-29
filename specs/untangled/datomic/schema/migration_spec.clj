(ns untangled.datomic.schema.migration-spec
  (:require
    [untangled.datomic.schema :as schema]
    [untangled-spec.core :refer [specification assertions when-mocking component behavior]]
    [untangled.datomic.test-helpers :refer [with-db-fixture]]
    [untangled.datomic.impl.util :as util]
    [clojure.test :refer [is]]))

(specification "all-migrations"
  (behavior "INTEGRATION - finds migrations that are in the correct package and ignores the template"
    (assertions
      (schema/all-migrations "untangled.datomic.fixtures.migrations")
      => '(
            {:untangled.datomic.fixtures.migrations/A {:txes [[{:item 1}]]}}
            {:untangled.datomic.fixtures.migrations/B {:txes [[{:item 2}]]}})))

  (behavior "does not find migrations that are in other packages"
    (let [mig1 "some.migration1"
          mig2 "some.migration2"]
      (when-mocking
        (util/load-namespaces "my.crap") => ['mig1 'mig2]

        (assertions
          (schema/all-migrations "my.crap") => '()))))

  (behavior "skips generation and complains if the 'transactions' function is missing."
    (when-mocking
      (util/namespace-name _) => "my.crap.A"
      (util/load-namespaces "my.crap") => ['my.crap.A]
      (ns-resolve _ _) => nil

      (assertions
        (schema/all-migrations "my.crap") => '())))

  (behavior "skips the migration and reports an error if the 'transactions' function fails to return a list of lists"
    (when-mocking
      (util/namespace-name :..migration1..) => "my.crap.A"
      (util/load-namespaces "my.crap") => [:..migration1..]
      (ns-resolve :..migration1.. 'transactions) => (fn [] [{}])
      (assertions
        (schema/all-migrations "my.crap") => '())))

  (behavior "skips the migration named 'template'"
    (when-mocking
      (util/namespace-name :..migration1..) => "my.crap.template"
      (util/load-namespaces "my.crap") => [:..migration1..]

      (assertions
        (schema/all-migrations "my.crap") => '()))))
