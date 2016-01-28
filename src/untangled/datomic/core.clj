(ns untangled.datomic.core
  (:require [datomic.api :as d]
            [com.stuartsierra.component :as component]
            [untangled.datomic.impl.components :as comp]
            ))

(defn retract-datomic-entity [connection entity-id] @(d/transact connection [[:db.fn/retractEntity entity-id]]))

(defn resolve-ids [new-db omids->tempids tempids->realids]
  (reduce
    (fn [acc [cid dtmpid]]
      (assoc acc cid (d/resolve-tempid new-db tempids->realids dtmpid)))
    {}
    omids->tempids))

(defn replace-ref-types [dbc refs m]
  "@dbc   the database to query
   @refs  a set of keywords that ref datomic entities, which you want to access directly
          (rather than retrieving the entity id)
   @m     map returned from datomic pull containing the entity IDs you want to deref"
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
   (comp/map->DatabaseComponent {:db-name database-key
                                     :config  {:value {:datomic config}}}))
  ([database-key]
   (component/using
     (comp/map->DatabaseComponent {:db-name database-key})
     [:config :logger])))
