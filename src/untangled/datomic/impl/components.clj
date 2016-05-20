(ns untangled.datomic.impl.components
  (:require [com.stuartsierra.component :as component]
            [datomic-toolbox.core :as dt]
            [datomic.api :as datomic]
            [taoensso.timbre :refer [info fatal]]
            [untangled.datomic.schema :as schema]
            [untangled.datomic.protocols :refer [Database]]))



(defn- run-migrations [migration-ns kw conn]
  (info "Applying migrations " migration-ns "to" kw "database.")
  (schema/migrate conn migration-ns))

(defn load-datomic-toolbox-helpers [db-url]
  (dt/configure! {:uri db-url :partition :db.part/user})
  (dt/install-migration-schema)
  (dt/run-migrations "datomic-toolbox-schemas"))

(defrecord DatabaseComponent [db-name connection seed-result config]
  Database
  (get-connection [this] (:connection this))
  (get-db-config [this]
    (let [config (-> this :config :value :datomic)
          db-config (-> config :dbs db-name)]
      (assert (-> config :dbs)
        "Missing :dbs of app config.")
      (assert (:url db-config)
        (str db-name " has no URL in dbs of app config."))
      (assert (:schema db-config)
        (str db-name " has no Schema in dbs of app config."))
      (-> db-config
        (clojure.set/rename-keys {:schema    :migration-ns
                                  :auto-drop :drop-on-stop})
        (assoc :migrate-on-start (boolean
                                   (or (:auto-migrate config)
                                     (:auto-migrate db-config)))))))
  (get-info [this]
    (let [{:keys [url seed-result migration-ns]} this]
      {:name           db-name :url url
       :seed-result    seed-result
       :schema-package migration-ns}))

  component/Lifecycle
  (start [this]
    (let [{:keys [migrate-on-start url
                  seed-function migration-ns]} (.get-db-config this)
          created (datomic/create-database url)
          c (datomic/connect url)]
      (when migrate-on-start
        (info "Ensuring core schema is defined")
        (schema/run-core-schema c)
        (info "Running migrations on" db-name)
        (load-datomic-toolbox-helpers url)
        (run-migrations migration-ns db-name c))
      (cond-> (assoc this :connection c)
        (and created seed-function)
        (assoc :seed-result
               (do (info "Seeding database" db-name)
                   (seed-function c))))))
  (stop [this]
    (info "Stopping database" db-name)
    (let [{:keys [drop-on-stop url]} (.get-db-config this)]
      (when drop-on-stop
        (info "Deleting database" db-name url)
        (datomic/delete-database url)))
    (assoc this :connection nil)))
