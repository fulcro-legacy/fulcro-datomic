(ns untangled.datomic.schema.validation-spec
  (:require
    [untangled.datomic.schema :as schema]
    [untangled-spec.core :refer [specification assertions when-mocking component behavior]]
    [datomic.api :as datomic]
    [seeddata.auth :as a]
    [untangled.datomic.test-helpers :as test-helpers :refer [with-db-fixture]]
    [resources.datomic-schema.validation-schema.initial]
    [clojure.test :refer [is]])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent ExecutionException)))

(defn- user-entity-id [conn email]
  (datomic/q '[:find ?e . :in $ ?v :where [?e :user/email ?v]] (datomic/db conn) email))

(defn- seed-validation [conn]
  (let [entities (concat
                   (a/create-base-user-and-realm)
                   [[:db/add :datomic.id/user1 :user/realm :datomic.id/realm1] [:db/add :datomic.id/user2 :user/realm :datomic.id/realm1]]
                   [(test-helpers/generate-entity {:db/id            :datomic.id/prop-entitlement
                                                   :entitlement/kind :entitlement.kind/property
                                                   })
                    (test-helpers/generate-entity {:db/id            :datomic.id/comp-entitlement
                                                   :entitlement/kind :entitlement.kind/component
                                                   })])]
    (test-helpers/link-and-load-seed-data conn entities)))

(specification "as-set"
  (behavior "converts scalars to singular sets"
    (assertions
      (schema/as-set 1) => #{1}))
  (behavior "converts lists to sets"
    (assertions
      (schema/as-set '(1 2 2)) => #{1 2}))
  (behavior "converts vectors to sets"
    (assertions
      (schema/as-set [1 2 2]) => #{1 2}))
  (behavior "leaves sets as sets"
    (assertions
      (schema/as-set #{1 2}) => #{1 2}))
  (behavior "throws an exception if passed a map"
    (assertions
      (schema/as-set {:a 1}) =throws=> (AssertionError #"does not work on maps"))))

(specification "Attribute derivation"
  (with-db-fixture dbcomp
    (let [c (:connection dbcomp)
          db (datomic/db c)
          id-map (-> dbcomp :seed-result)
          realm-id (:datomic.id/realm1 id-map)
          user1id (:datomic.id/user1 id-map)
          user2id (:datomic.id/user2 id-map)
          compe-id (:datomic.id/comp-entitlement id-map)
          prope-id (:datomic.id/prop-entitlement id-map)]
      (behavior "foreign-attributes can find the allowed foreign attributes for an entity type"
        (assertions
          (schema/foreign-attributes db :user) => #{:authorization-role/name}) ; see schema in initial.clj )

        (behavior "foreign-attributes accepts a string for kind"
          (assertions
            (schema/foreign-attributes db "user") => #{:authorization-role/name}))

        (behavior "core-attributes can find the normal attributes for an entity type"
          (assertions
            (schema/core-attributes db :user) => #{:user/password :user/property-entitlement :user/user-id :user/email
                                              :user/is-active :user/validation-code
                                              :user/realm :user/authorization-role}))

        (behavior "core-attributes accepts a string for kind"
          (assertions
            (schema/core-attributes db "user") => #{:user/password :user/property-entitlement :user/user-id :user/email
                                               :user/is-active :user/validation-code
                                               :user/realm :user/authorization-role}))

        (behavior "all-attributes can find all allowed attributes for an entity type including foreign"
          (assertions
            (schema/all-attributes db :user) => #{:user/password :user/property-entitlement :user/user-id :user/email
                                             :user/is-active :user/validation-code
                                             :user/realm :user/authorization-role :authorization-role/name}))

        (behavior "definitive-attributes finds all of the definitive attributes in the schema."
          (assertions
            (schema/definitive-attributes db) => #{:user/user-id :realm/account-id}))

        (behavior "entity-types finds all of the types that a given entity conforms to"
          (let [new-db (:db-after (datomic/with db [[:db/add user1id :realm/account-id "boo"]]))
                user-realm (datomic/entity new-db user1id)]
            (assertions
              (schema/entity-types new-db user-realm) => #{:user :realm})))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))

(specification "Validation"
  (with-db-fixture dbcomp
    (let [c (:connection dbcomp)
          db (datomic/db c)
          id-map (-> dbcomp :seed-result)
          realm-id (:datomic.id/realm1 id-map)
          user1id (:datomic.id/user1 id-map)
          user2id (:datomic.id/user2 id-map)
          compe-id (:datomic.id/comp-entitlement id-map)
          prope-id (:datomic.id/prop-entitlement id-map)]
      (component "reference-constraint-for-attribute"
        (behavior "returns nil for non-constrained attributes"
          (assertions
            (schema/reference-constraint-for-attribute db :user/name) => nil))

        (behavior "finds constraint data about schema"
          (assertions
            (select-keys (schema/reference-constraint-for-attribute db :user/property-entitlement) [:constraint/references :constraint/with-values])
            => {:constraint/references  :entitlement/kind
                :constraint/with-values #{:entitlement.kind/property-group
                                          :entitlement.kind/property
                                          :entitlement.kind/all-properties}}))

        (behavior "finds constraint data even when there are no constrained values "
          (assertions
            (:constraint/references (schema/reference-constraint-for-attribute db :realm/subscription)) => :subscription/name))

        (behavior "includes the referencing (source) attribute"
          (assertions
            (:constraint/attribute (schema/reference-constraint-for-attribute db :realm/subscription)) => :realm/subscription)))

      (behavior "entity-has-attribute? can detect if an entity has an attribute"
        (when-mocking
          (datomic/db c) => db
          (let [user1-eid (user-entity-id c "user1@example.net")]
            (assertions
              (schema/entity-has-attribute? db user1-eid :user/user-id) => true
              (schema/entity-has-attribute? db user1-eid :realm/account-id) => false))))

      (component "entities-in-tx"
        (behavior "finds the resolved temporary entity IDs and real IDs in a transaction"
          (let [newuser-tempid (datomic/tempid :db.part/user)
                datoms [[:db/add newuser-tempid :user/email "sample"] [:db/add user2id :user/email "sample2"]]
                result (datomic/with db datoms)
                newuser-realid (datomic/resolve-tempid (:db-after result) (:tempids result) newuser-tempid)]
            (assertions
              (schema/entities-in-tx result true) => #{newuser-realid user2id})))

        (behavior "can optionally elide entities that were completely removed"
          (let [email (:user/email (datomic/entity db user1id))
                datoms [[:db.fn/retractEntity prope-id] [:db/retract user1id :user/email email]]
                result (datomic/with db datoms)]
            (assertions
              (schema/entities-in-tx result) => #{user1id}
              (schema/entities-in-tx result true) => #{user1id prope-id})))

        (behavior "includes entities that were updated because of reference nulling"
          (let [datoms [[:db.fn/retractEntity user2id]]
                result (datomic/with db datoms)]
            (assertions
              (schema/entities-in-tx result) => #{realm-id}      ; realm is modified because it refs the user
              (schema/entities-in-tx result true) => #{user2id realm-id}))))

      (behavior "entities-that-reference returns the IDs of entities that reference a given entity"
        (assertions
          (into #{} (schema/entities-that-reference db realm-id)) => #{user1id user2id}))

      (component "invalid-references"
        (behavior "returns nil when all outgoing MANY references are valid"
          (assertions
            (schema/invalid-references db realm-id) => nil))

        (behavior "returns nil when all outgoing ONE references are valid"
          (assertions
            (schema/invalid-references db user1id) => nil))

        (behavior "returns a list of bad references when outgoing references are incorrect"
          (let [new-db (-> (datomic/with db [[:db/add user1id :user/realm compe-id]
                                       [:db/add user2id :user/property-entitlement compe-id]])
                         :db-after)]
            (assertions
              (select-keys (first (schema/invalid-references new-db user1id)) [:target-attr :reason])
              => {:target-attr :realm/account-id :reason "Target attribute is missing"}

              (select-keys (first (schema/invalid-references new-db user2id)) [:target-attr :reason])
              => {:target-attr :entitlement/kind :reason "Target attribute has incorrect value"}))))

      (component "invalid-attributes"
        (behavior "returns nil when the entity contains only valid attributes"
          (assertions
            (schema/invalid-attributes db (datomic/entity db user1id)) => nil))

        (behavior "returns a set of invalid attributes on an entity"
          (let [new-db (:db-after (datomic/with db [[:db/add user1id :subscription/name "boo"]]))]
            (assertions
              (schema/invalid-attributes new-db (datomic/entity new-db user1id)) => #{:subscription/name})))

        (behavior "allows a definitive attribute to extend the allowed attributes"
          (let [new-db (:db-after (datomic/with db [[:db/add user1id :realm/account-id "boo"]]))]
            (assertions
              (schema/invalid-attributes new-db (datomic/entity new-db user1id)) => nil))))

      (component "validate-transaction"
        (behavior "returns true if the transaction is empty"
          (let [new-db (datomic/with db [])]
            (assertions
              (schema/validate-transaction new-db) => true)))

        (behavior "returns true if the transaction is valid"
          (let [new-db (datomic/with db [[:db/add user1id :user/property-entitlement prope-id]])]
            (assertions
              (schema/validate-transaction new-db) => true)))

        (behavior
          "throws an invalid reference exception when the transaction has a bad reference in the main modification"
          (let [new-db (datomic/with db [[:db/add user1id :user/property-entitlement compe-id]])]
            (assertions
              (schema/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References"))))

        (behavior
          "throws an invalid reference exception when the transaction causes an existing reference to become invalid by removing the targeted attribute"
          (let [new-db (datomic/with db [[:db/retract realm-id :realm/account-id "realm1"]])]
            (assertions
              (schema/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References" #(= :realm/account-id (-> % ex-data :problems first :target-attr))))))

        (behavior
          "throws an invalid reference exception when the transaction causes an existing reference to become invalid by changing the targeted attribute value"
          (let [valid-db (:db-after (datomic/with db [[:db/add user1id :user/property-entitlement prope-id]]))
                new-db (datomic/with valid-db [[:db/add user1id :user/property-entitlement compe-id]])]
            (assertions
              (schema/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References" #(re-find #"incorrect value" (-> % ex-data :problems first :reason)))
              (schema/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References" #(= :entitlement/kind (-> % ex-data :problems first :target-attr))))))

        (behavior
          "throws an invalid attribute exception when an entity affected by the transaction ends up with a disallowed attribute"
          (let [new-db (datomic/with db [[:db/add user1id :subscription/name "boo"]])]
            (assertions
              (schema/validate-transaction new-db true) =throws=> (ExceptionInfo #"Invalid Attribute"))))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))

;; IMPORTANT NOTE: These NON-integration tests are a bit heavy (as they have to muck about with the internals of the function
;; under test quite a bit); however, they are the only way to prove that both paths of validation (optimistic and
;; pessimistic) are correct.
(specification "vtransact"
  (behavior "always validates references and attributes on the peer"
    (when-mocking
      (datomic/db _) => "conn"
      (datomic/basis-t _) => 1
      (datomic/with _ _) => true
      (schema/validate-transaction _ true) => true
      (datomic/transact _ _) => (future [])
      (assertions
        (future? (schema/vtransact "conn" [])) => true)))

  (behavior "skips transact when optimistic validation fails"
    (when-mocking
      (datomic/db _) =2x=> "db"
      (datomic/with _ []) =2x=> {}
      (datomic/basis-t _) => 1
      (schema/validate-transaction {} true) =1x=> (throw (ex-info "Validation failed" {}))
      (assertions
        @(schema/vtransact "conn" []) =throws=> (ExecutionException #"Validation failed"))))

  (behavior "optimistically applies changes via the transactor while enforcing version known at peer"
    (let [tx-data [[:db/add 1 :blah 2]]
          optimistic-version 1]
      (when-mocking
        (datomic/db _) => "db"
        (datomic/basis-t _) => optimistic-version
        (datomic/with _ tx-data) => :anything
        (schema/validate-transaction _ true) => true
        (datomic/transact conn tx) => (do
                                  (is (= (-> tx first) [:ensure-version optimistic-version]))
                                  (is (= (-> tx rest) tx-data))
                                  (future []))
        (schema/vtransact :connection tx-data))))

  (behavior "completes if the optimistic update succeeds"
    (let [tx-data [[:db/add 1 :blah 2]]
          optimistic-version 1
          transact-result (future [])]
      (when-mocking
        (datomic/db _) => "db"
        (datomic/basis-t _) => optimistic-version
        (datomic/with _ tx-data) => "..result.."
        (schema/validate-transaction _ true) => true
        (datomic/transact _ anything) => transact-result
        (assertions (schema/vtransact "connection" tx-data) => transact-result))))

  (behavior "reverts to a pessimistic application in the transactor if optimistic update fails"
    (let [tx-data [[:db/add 1 :blah 2]]
          optimistic-version 1
          tx-result-1 (future (throw (ex-info "Bad Version" {})))
          tx-result-2 (future [])]
      (when-mocking
        (datomic/db conn) => "db"
        (datomic/basis-t db) => optimistic-version
        (datomic/with db tx-data) => "result"
        (schema/validate-transaction result true) => true
        (datomic/transact conn tx) =1x=> (do
                                     (is (= (-> tx first) [:ensure-version optimistic-version])) ;; first attempt with ensured version fails
                                     (is (= (-> tx rest) tx-data))
                                     tx-result-1)
        (datomic/transact conn tx) =1x=> (do
                                     (is (= (-> tx first) [:constrained-transaction tx-data])) ;; second attempt constrained
                                     tx-result-2)

        (assertions (schema/vtransact "connection" tx-data) => tx-result-2))))

  (with-db-fixture dbcomp
    (let [c (:connection dbcomp)
          db (datomic/db c)
          id-map (-> dbcomp :seed-result)
          user1id (:datomic.id/user1 id-map)
          bad-attr-tx [[:db/add user1id :subscription/name "data"]]]

      (behavior "succeeds against a real database"
        (assertions
          (user-entity-id c "user1@example.net") => user1id)
        (schema/vtransact c [[:db/add user1id :user/email "updated@email.net"]]) ;; update user email address
        (assertions
          ;; can't find the old one
          (user-entity-id c "user1@example.net") => nil
          ;; CAN find the new one!
          (user-entity-id c "updated@email.net") => user1id))

      (behavior "fails against a real database when invalid attribute"
        (assertions @(schema/vtransact c bad-attr-tx) =throws=> (ExecutionException #"Invalid Attribute"
                                                             #(contains? (-> % .getCause ex-data :problems) :subscription/name)))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))

