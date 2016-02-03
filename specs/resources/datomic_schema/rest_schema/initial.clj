(ns resources.datomic-schema.rest-schema.initial
  (:require [untangled.datomic.schema :as schema]
            [datomic.api :as d]))

(defn transactions []
  [
   (schema/generate-schema
            [
             (schema/schema realm
                       (schema/fields
                         [realm-id :string :unique-identity :definitive "realm-id-doc"]
                         [realm-name :string "realm-name-doc"]
                         [user :ref :many {:references :user/user-id} "realm-user-doc"]
                         [subscription :ref :many :component {:references :subscription/name} "realm-subscription-doc"]
                         )
                       )
             (schema/schema subscription
                       (schema/fields
                         [name :string :unique-identity :definitive]
                         [component :ref :one :component {:references :component/name}]
                         )
                       )

             (schema/schema component
                       (schema/fields
                         [name :string :unique-identity :definitive]
                         )
                       )

             (schema/schema user
                       (schema/fields
                         [user-id :uuid :unique-identity :definitive]
                         [realm :ref :one {:references :realm/realm-id}]
                         [email :string :unique-value]
                         [password :string :unpublished]
                         )
                       )
             ])
     (schema/entity-extensions :user "user entity doc" [])
     (schema/entity-extensions :realm "realm entity doc" [])
     (schema/entity-extensions :subscription "subscription entity doc" [])
     (schema/entity-extensions :component "component entity doc" [:realm/realm-id :realm/realm-name])
   ]
  )
