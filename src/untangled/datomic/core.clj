(ns untangled.datomic.core
  (:require [datomic.api :as d]
            [clojure.tools.cli :refer [cli]]
            [com.stuartsierra.component :as component]
            [untangled.datomic.impl.components :as comp]
            [untangled.datomic.impl.util :as util]
            [taoensso.timbre :as timbre]
            [untangled.datomic.schema :as schema]
            [untangled.datomic.protocols :as p]))

(defn retract-datomic-entity [connection entity-id] @(d/transact connection [[:db.fn/retractEntity entity-id]]))

(defn resolve-ids [new-db client-tempids->server-tempids server-tempids->realids]
  (reduce
    (fn [acc [cid dtmpid]] (assoc acc cid (d/resolve-tempid new-db server-tempids->realids dtmpid)))
    {}
    client-tempids->server-tempids))

(defn query
  "Runs a datomic query against the database component. Same basic structure as datomic.api/q, AND
  you may use the `datomic.id` namespaced keywords used to seed the component as the values of bound variables
  within `in` clauses. Only supports one bound database value for `in` clauses.

  e.g. (query
          '[:find ?a :in $ ?id :where [?e :author/address ?a]
                                      [?e :author/name ?id]]
          database-component
          :datomic.id/author-name)"
  [query database-comp & bound-variables]
  (let [db (d/db (p/get-connection database-comp))
        seeded-id-map (:seed-result (p/get-info database-comp))
        real-ids (map #(get seeded-id-map % %) bound-variables)]

    (apply d/q query db real-ids)))

(defn replace-ref-types
  "@dbc   the database to query
   @refs  a set of keywords that ref datomic entities, which you want to access directly
          (rather than retrieving the entity id)
   @m     map returned from datomic pull containing the entity IDs you want to deref"
  [dbc refs m]
  (clojure.walk/postwalk
    (fn [arg]
      (if (and (coll? arg) (refs (first arg)))
        (update-in arg [1] (comp :db/ident (partial d/entity dbc) :db/id))
        arg))
    m))

(defn datomicid->tempid [m x]
  (let [inverter (clojure.set/map-invert m)]
    (clojure.walk/postwalk
      #(if-let [tid (get inverter %)]
        tid %)
      x)))

(defn build-database
  "Build a database component. If you specify a config, then none will be injected. If you do not, then this component
  will expect there to be a `:config` component to inject."
  ([database-key config]
   (component/using
     (comp/map->DatabaseComponent {:db-name database-key
                                   :config  {:value {:datomic config}}})
     [:logger]))
  ([database-key]
   (component/using
     (comp/map->DatabaseComponent {:db-name database-key})
     [:config :logger])))

(defn main-handler [config args]
  (let [[opts args banner]
        (cli args
          ["-h" "--help" "Print this help." :default false :flag true]
          ["-v" "--verbose" "Be verbose." :default false :flag true]
          ["-l" "--list-dbs" "List databases that can be migrated." :default false :flag true]
          ["-m" "--migrate" "Apply migrations to a database, or `all` for all databases." :default nil]
          ["-s" "--migration-status" (str "Check a whether a database has all possible migrations. "
                                       "Use `all` to check all databases.") :default nil])
        argument (util/single-arg (select-keys opts [:list-dbs :migrate :migration-status :help]))]
    (if-not argument
      (do (timbre/fatal "Only one argument at a time is supported.") (println banner))
      (let [target-db (-> argument first second)
            db-config (if (= target-db "all")
                        config
                        (->> target-db keyword (get config)))
            dbnames (mapv name (keys config))]
        (cond (:list-dbs opts) (do (timbre/info "Available databases configured for migration:\n" dbnames)
                                   ;; return dbnames for testing only
                                   dbnames)
              (:migrate opts) (schema/migrate-all db-config)
              (:migration-status opts) (let [migs (schema/migration-status-all db-config (:verbose opts))]
                                         (if (empty? migs)
                                           (timbre/info "Database conforms to all migrations!")
                                           (timbre/warn "Database does NOT conform to these migrations: " migs))))))))
