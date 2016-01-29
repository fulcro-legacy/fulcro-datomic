(ns resources.datomic-schema.validation-schema.initial
  (:require
    datomic.api
    [untangled.datomic.schema :as schema]
    )
  )

(defn transactions []
  (concat
    [
     (schema/generate-schema
       [
        (schema/dbfn constrained-delete [db eid] :db.part/user
                (let [attrs (keys (datomic.api/entity db eid))
                      references-to-attr (fn [db attr eid]
                                           (datomic.api/q '[:find [?e2 ...]
                                                            :in $ ?target ?eid
                                                            :where [?e2 ?v ?eid]
                                                            [?e :db/ident ?v]
                                                            [?e :constraint/references ?target]] db attr eid)
                                           )
                      refs     (clojure.core/mapcat #(references-to-attr db % eid) attrs)
                      ]
                  (if (empty? refs) [[:db.fn/retractEntity eid]] (throw (Exception. "CONSTRAINTED")))
                  )
                )
        (schema/schema realm
                  (schema/fields
                    [account-id :string :unique-identity :definitive]
                    [account-name :string]
                    [user :ref :many {:references :user/email}]
                    [property-group :ref :many :component] ;; realm-defined property groups
                    [custom-authorization-role :ref :many :component]
                    [subscription :ref :many :component {:references :subscription/name}]
                    )
                  )

        (schema/schema subscription
                  (schema/fields
                    [name :string :unique-identity]
                    [application :ref :one { :references :application/name }]
                    [component :ref :one { :references :component/name }]
                    ;; ... dates, etc?
                    )
                  )

        (schema/schema user
                 (schema/fields
                    [user-id :uuid :unique-identity :definitive]
                    [realm :ref :one { :references :realm/account-id }]
                    [email :string :unique-value]
                    [password :string]
                    [is-active  :boolean]
                    [validation-code :string]
                    [property-entitlement :ref :many
                     { :references :entitlement/kind
                      :with-values #{:entitlement.kind/property-group
                                     :entitlement.kind/property
                                     :entitlement.kind/all-properties }
                      }
                     ]
                    [authorization-role :ref :many]
                    )
                  )

        (schema/schema component
                  (schema/fields
                    [name :string]
                    ;; description of what this component does
                    [read-functionality :string]
                    [write-functionality :string]
                    )
                  )

        (schema/schema application
                  (schema/fields
                    [name :string :unique-identity]
                    [component :ref :many :component]
                    )
                  )

        (schema/schema property-group ;; owned by realm
                  (schema/fields
                    [name :string ]
                    [property :uuid :many ] ;; to property
                    )
                  )

        (schema/schema entitlement
                  (schema/fields
                    ;; The kind of entitlement.
                    [kind :enum [:all-properties :all-components :property :property-group
                                 :component :application] ]
                    ;; a property, propgroup, component, or application
                    [target :ref :one]
                    [target-property :uuid]

                    ;; Limits are ONLY used when kind is property-related, can
                    ;; optionally limit that property access to specific
                    ;; apps/components. E.g. Reach list builder might need to
                    ;; allow access to all properties, but those property's
                    ;; financials should not be exposed by giving property access
                    ;; to Reveal.
                    [limit-to-application :ref :many]
                    [limit-to-component :ref :many]

                    [permission :enum [:read :write]]
                    )
                  )

        (schema/schema authorization-role  ;; pre-defined set of entitlements under a (convenient) app-specific role name
                  (schema/fields
                    [application :ref :one]
                    [name :string]
                    [entitlement :ref :many :component]
                    )
                  )
        ])
     (schema/entity-extensions :user "A User" #{:authorization-role/name})
     ]
    ))
