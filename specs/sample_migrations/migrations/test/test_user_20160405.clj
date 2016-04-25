(ns sample-migrations.migrations.test.test-user-20160405
  (:require [untangled.datomic.schema :as s]))

(defn transactions []
  [(s/generate-schema
     [(s/schema address
        (s/fields
          [zipcode :long]))
      (s/schema user
        (s/fields
          [username :string]
          [address :ref :one]
          [email :string :unique-identity]
          [status :enum [:active :pending]]
          ))]
     {:index-all? true})])
