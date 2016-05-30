(ns untangled.datomic.schema-spec
  (:require
    [untangled-spec.core :refer [specification assertions when-mocking component behavior]]
    [untangled.datomic.schema :as src]
    [datomic.api :as d]))

(specification "gen-schema"
  (when-mocking
    (d/tempid _) => :tempid
    (assertions
      (src/map->schema {:foo {:bar [:ref :many]
                              :qux [:string]}
                        :bar {:foo [:string]
                              :qux [:uuid]}})
      => (src/generate-schema
           [(src/schema foo
                        (src/fields
                          [bar :ref :many]
                          [qux :string]))
            (src/schema bar
                        (src/fields
                          [foo :string]
                          [qux :uuid]))]))))
