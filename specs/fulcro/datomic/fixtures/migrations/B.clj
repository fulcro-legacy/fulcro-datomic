(ns fulcro.datomic.fixtures.migrations.B
  (:require [fulcro.datomic.schema :as s]))

(defn migrate-data [dummy-conn] (reset! dummy-conn true))

(defn transactions []
  (s/migrate-with migrate-data [[{:item 2}]]))
