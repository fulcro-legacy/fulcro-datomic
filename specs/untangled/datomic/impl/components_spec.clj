(ns untangled.datomic.impl.components-spec
  (:require [com.stuartsierra.component :as component]
            [clojure.test :as t]
            [untangled.datomic.core :refer [build-database]]
            [untangled-spec.core :refer
             [specification assertions when-mocking component behavior]]
            [untangled.datomic.schema :as schema]
            [untangled.datomic.impl.components :as comp]
            [datomic-toolbox.core :as dt]
            [datomic.api :as d]
            [taoensso.timbre :as timbre]))

(t/use-fixtures
  :once #(timbre/with-merged-config
           {:ns-blacklist ["untangled.datomic.impl.components"]}
           (%)))

(def default-db-name :db1)
(def default-db-url "db1-url")
(def default-schema "schema.default")
(defn make-config [m]
  {:datomic {:dbs {default-db-name (merge {:url    default-db-url
                                           :schema default-schema}
                                     m)}}})

(def default-config
  (make-config {:auto-drop true}))

(def migrate-specfic-config
  (make-config {:auto-migrate true}))

(def migrate-all-config
  (let [config-sans-migrate (update-in migrate-specfic-config [:datomic :dbs default-db-name] dissoc :auto-migrate)]
    (assoc-in config-sans-migrate
      [:datomic :auto-migrate] true)))

(def seed-result :a-tree!)
(def default-seed-function (fn [_] seed-result))
(def seed-config
  (make-config {:seed-function default-seed-function}))

(defn start-system
  ([] (start-system default-config))
  ([cfg]
   (-> (component/system-map
         :logger {}
         :config {:value cfg}
         :db (build-database default-db-name))
     .start)))

(specification "DatabaseComponent"

  (behavior "implements Database"
    (assertions
      (satisfies? untangled.datomic.protocols/Database (build-database "a-db-name")) => true))

  (behavior "implements component/Lifecycle"
    (assertions
      (satisfies? component/Lifecycle (build-database "a-db-name")) => true))

  (behavior ".start loads the component"
    (when-mocking
      (d/create-database default-db-url) => true
      (d/connect default-db-url) => true
      (assertions
        (some #(= :config %) (-> (start-system) :db keys)) => true)))

  (behavior ".start can auto-migrate if configured for all databases"
    (when-mocking
      (dt/install-migration-schema) => true
      (dt/run-migrations _) => true
      (d/create-database default-db-url) => true
      (d/connect default-db-url) => true
      (schema/run-core-schema anything) => true
      (comp/run-migrations anything anything anything) => true
      (assertions
        (if (start-system migrate-all-config) true) => true))
    (behavior "and stores the resulting schema in the component"
      (when-mocking
        (d/create-database default-db-url) => true
        (d/connect default-db-url) => true
        (schema/run-core-schema c) => true
        (comp/load-datomic-toolbox-helpers url) => true
        (comp/run-migrations _ _ _) => :fake/schema
        (assertions
          (-> (start-system migrate-all-config)
            :db :schema)
          => :fake/schema))))

  (behavior ".start can auto-migrate if configured for a specific database"
    (when-mocking
      (dt/install-migration-schema) => true
      (dt/run-migrations _) => true
      (d/create-database default-db-url) => true
      (d/connect default-db-url) => true
      (schema/run-core-schema anything) => true
      (comp/run-migrations anything anything anything) => true
      (assertions
        (if (start-system migrate-specfic-config) true) => true)))

  (behavior ".start runs seed-function if it needs to"
    (when-mocking
      (d/create-database default-db-url) => true
      (d/connect default-db-url) => true
      (assertions
        (-> (start-system seed-config) :db :seed-result) => seed-result))
    (behavior "if seed-functions can take 2 arguments, they are also passed their db config"
      (when-mocking
        (d/create-database default-db-url) => true
        (d/connect default-db-url) => :fake/conn
        (let [cfg (make-config {:seed-function
                                (fn [c db-cfg]
                                  (assertions
                                    c => :fake/conn
                                    db-cfg => {:url "db1-url",
                                               :migration-ns "schema.default",
                                               :migrate-on-start false})
                                  :ok)})]
          (assertions
            (-> (start-system cfg) :db :seed-result) => :ok
            "can also take a var instead of a fn"
            (comp/arities #'start-system) => [0 1])))))

  (behavior ".stop stops the component"
    (when-mocking
      (d/create-database anything) => true
      (d/connect anything) => true
      (d/delete-database anything) => true
      (assertions
        (-> (start-system) .stop :db :connection) => nil)))

  (behavior "propagates any errors up to the consumer"
    (when-mocking
      (d/create-database anything) => true
      (d/connect anything) => true
      (let [test-seed-fn (fn [this-db] (throw (ex-info "ACK" {})))]
        (assertions
          (try (start-system (make-config {:seed-function test-seed-fn}))
               (catch Exception e
                 (throw (.getCause e))))
          =throws=> (clojure.lang.ExceptionInfo #"ACK"))))))
