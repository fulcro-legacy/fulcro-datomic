(ns untangled.datomic.fixtures.migrations.B
  (:require [untangled.datomic.schema :as s]))

(defn migrate-data [dummy-conn] (reset! dummy-conn true))

(defn transactions []
  (s/migrate-with migrate-data [[{:item 2}]]))
