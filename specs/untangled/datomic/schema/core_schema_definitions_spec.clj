(ns untangled.datomic.schema.core-schema-definitions-spec
  (:require
    [untangled.datomic.schema :as schema]
    [untangled-spec.core :refer [specification assertions when-mocking component behavior]]
    [datomic.api :as datomic]
    [seeddata.auth :as a]
    [untangled.datomic.test-helpers :as test-helpers :refer [with-db-fixture]]
    [resources.datomic-schema.validation-schema.initial]
    [resources.datomic-schema.rest-schema.initial]
    [untangled.datomic.impl.util :as util]
    [clojure.test :refer [is]])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent ExecutionException)))

(defn- user-entity-id [conn email]
  (datomic/q '[:find ?e .
               :in $ ?v
               :where [?e :user/email ?v]]
    (datomic/db conn) email))

(defn- seed-validation [conn]
  (let [entities (a/create-base-user-and-realm)]
    (test-helpers/link-and-load-seed-data conn entities)))

(def ^:private always
  (constantly true?))

(def ^:private anything
  true)

(specification
  ;; TODO:  ^:integration
  "ensure-version Datomic function"
  (with-db-fixture dbcomp

    (let [c (:connection dbcomp)
          db (datomic/db c)
          id-map (-> dbcomp :seed-result)
          realm-id (:datomic.id/realm1 id-map)
          user1id (:datomic.id/user1 id-map)
          user2id (:datomic.id/user2 id-map)]

      (behavior
        ;; TODO: ^:integration
        "allows a transaction to run if the version of the database is unchanged"

        (let [t1 (datomic/basis-t db)]
          (assertions
            (user-entity-id c "user1@example.net") => user1id
            (datomic/transact c [[:ensure-version t1] [:db/add user1id :user/email "updated@email.net"]]) =fn=> always
            (user-entity-id c "user1@example.net") => nil
            (user-entity-id c "updated@email.net") => user1id)
          ;; "Undo"
          (datomic/transact c [[:db/add user1id :user/email "user1@example.net"]])))

      (behavior
        ;; TODO: ^:integration
        "prevents a transaction from running if the version of the database has changed"
        (assertions
          (user-entity-id c "user1@example.net") => user1id)

        (let [t1 (datomic/basis-t db)
              db2 @(datomic/transact c [[:db/add user1id :user/email "updated@email.com"]])]
          (assertions
            @(datomic/transact c [[:ensure-version t1] [:db/add user1id :user/email "updated@email.net"]])
            =throws=> (ExecutionException #"does not match")
            (user-entity-id c "updated@email.net") => nil
            (user-entity-id c "updated@email.com") => user1id)))

      (behavior
        "datomic-toolbox database functions are installed."
        (doall (map #(is (contains? (datomic/touch (datomic/entity db %)) :db/fn)) [:transact :assert-empty :assert-equal]))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))

(specification
  ;; TODO: ^:integration
  "constrained-transaction Datomic function"
  (with-db-fixture dbcomp

    (let [c (:connection dbcomp)
          db (datomic/db c)
          id-map (-> dbcomp :seed-result)
          realm-id (:datomic.id/realm1 id-map)
          user1id (:datomic.id/user1 id-map)
          user2id (:datomic.id/user2 id-map)]

      (behavior "calls validate-transaction WITHOUT attribute check"
        (let [tx-data [:db/add user1id :user/email "updated@email.net"]]
          (when-mocking
            (datomic/with anything tx-data) => :..tx-result..
            (schema/validate-transaction :..tx-result.. false) => anything

            (assertions
              (datomic/transact c [[:constrained-transaction tx-data]]) =fn=> identity))
          ;; "Undo"
          (datomic/transact c [[:db/add user1id :user/email "user1@example.net"]])))

      (behavior
        ;; TODO: ^:integration
        "prevents invalid transactions from running"
        (let [tx-data [[:db/add realm-id :realm/subscription user1id]]]
          (assertions
            @(datomic/transact c [[:constrained-transaction tx-data]])
            =throws=> (ExecutionException #"Invalid References"))))

      (behavior
        ;; TODO: ^:integration
        "allows valid transactions to run"
        (let [tx-data [[:db/add user1id :user/email "updated@email.net"]]]
          (assertions
            (user-entity-id c "user1@example.net") => user1id
            (datomic/transact c [[:constrained-transaction tx-data]]) =fn=> identity
            (user-entity-id c "user1@example.net") => nil
            (user-entity-id c "updated@email.net") => user1id))
        ;; "Undo"
        (datomic/transact c [[:db/add user1id :user/email "user1@example.net"]])))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))
