(ns untangled.datomic.test-helpers
  (:require
    [datomic.api :as d]
    [com.stuartsierra.component :as component]
    [untangled.datomic.core :refer [build-database]]
    [taoensso.timbre :as t]
    [clojure.walk :as walk]
    [clojure.math.combinatorics :as combo]
    [clojure.walk :as walk]))



(defn db-fixture
  "Create a test fixture version (in-memory database) of a database. Such a
  database will be auto-migrated to the current version of the schema in the
  given (optional) namespace, and the
  given seed-fn will be run as part of the database startup (see the database
  component in datahub for details on seeding).
  "
  [db-key & {:keys [migration-ns seed-fn]}]
  (let [uri (str "datomic:mem://db-fixture-" (name db-key))
        db-key-config (cond-> {:url uri :auto-drop true}
                        migration-ns (assoc :auto-migrate true :schema migration-ns)
                        seed-fn (assoc :seed-function seed-fn))
        db (build-database db-key {:dbs {db-key db-key-config}})]
    (d/delete-database uri)
    (component/start db)))

(defmacro with-db-fixture
  "
  Set up the specified database, seed it using seed-fn (see Database component),
  make it available as varname, run the given form, then clean up the database.

  Returns the result of the form.
  "
  [varname form & {:keys [migrations seed-fn log-level db-key] :or {log-level :fatal db-key :mockdb}}]
  `(t/with-level ~log-level
     (let [~varname (db-fixture ~db-key :migration-ns ~migrations :seed-fn ~seed-fn)]
       (try ~form (finally (component/stop ~varname))))))



(defmacro generate-entity
  "Generate a ready-to-link datomic object. The object data can take the form

       {
         :db/id :datomic.id/myid
         :attr  value
         :refattr  tempid | realid
       }

  Any scalar values will be used literally. Any database ID can be either a real
  datomic entity ID, or a :datomic.id/name placeholder. Using the same :datomic.id/name
  placeholder in multiple entities within a transaction will result in the exact
  same tempid when joined into a real transaction.

  As with normal entity maps in datomic, you may put values for many fields as
  a set, including tempids:

       { :db/id :datomic.id/myid
         :attr  value
         :refattrs #{ tempid realid realid tempid }
       }

  The primary purpose of this macro is to validate and add metadata to the entity.
  "
  [obj]
  (let [f *file*]
    (with-meta obj (assoc (meta &form) :file f))))

(defn is-tempid-keyword? [v] (and (keyword? v) (some-> v namespace (.startsWith "datomic.id"))))

(defn assign-temp-id
  "Scans item (which may be a map of attributes or a datomic :db/add list) for
  an ID field with a keyword namespaced within datomic.id (e.g.
  :datomic.id) as a value. If found, it requests a new temporary ID from
  datomic and remembers the association in the returned map.

  If the item is a map and the id-map already contains a mapping for the item,
  then this function will throw an AssertionError that includes the metadata of
  the item in question, which will include the file/line if the entity was
  created via the generate-entity macro.

  If the item is a datomic datom list, then no duplicate checking is applied, and
  new ids will be added to the map, while repeat entries will be ignored.
  "
  [id-map item]
  (cond
    (map? item) (let [new-id-mappings (atom id-map)]
                  (walk/prewalk #(if-let [id (and (map? %) (keyword? (:db/id %)) (is-tempid-keyword? (:db/id %)) (:db/id %))]
                                  (let [tempid (d/tempid :db.part/user)]
                                    (assert (not (contains? @new-id-mappings id)) (str "Entity uses a duplicate ID: " id (meta item)))
                                    (swap! new-id-mappings assoc id tempid)
                                    (assoc % :db/id tempid))
                                  %) item)
                  @new-id-mappings)
    (sequential? item)
    (let [id (nth item 1)]
      (if (and (is-tempid-keyword? id) (not (contains? id-map id)))
        (assoc id-map id (d/tempid :db.part/user))
        id-map))
    :otherwise (assert false "Invalid entry in data to link. Must be list or map")))

(defn replace-id [idmap entity value]
  (cond
    (and (is-tempid-keyword? value) (get idmap value nil)) (get idmap value)
    :otherwise (do
                 (assert (not (is-tempid-keyword? value)) (str "Missing ID " value " for entity " entity (meta entity)))
                 value)))

(defn assign-ids
  "Replaces any references to temporary IDs that exist in idmap with the actual
  tempid.

  The entity may be a map-form or a list-form (datomic datom).

  Returns an updated entity that has the correct temporary IDs.
  "
  [idmap entity]
  (walk/prewalk (partial replace-id idmap entity) entity))

(defn link-entities
  "Walks the given entities (list, set, vector) and:

  * ensures the tempids have a proper datomic temporary ID
  * ensures that a given tempid is NOT used as the ID for more than one entity
  * ensures that all references to tempIDs end up using the real datomic temporary
  ID for that entity.

  Returns a list of entities with their IDs properly linked.
  "
  [e]
  (assert (not (map? e)) "Argument must be a list, set, or vector")
  (let [tempids (reduce assign-temp-id {} (seq e))]
    {:items   (for [entry e] (assign-ids tempids entry))
     :tempids tempids}))

(defn link-and-load-seed-data
  "Links the given data (by temp IDs), and loads it into the database. This
  function returns a map from user-generated :datomic.id/... IDs to the read IDs
  that were used by the database.

  The datoms may be in map or list form (a list of lists, or a list of maps),
  and you can use generate-entities on the maps for better debugging support.
  "
  [conn datoms]
  (let [link (link-entities datoms)
        linked-data (:items link)
        assigned-tempids (:tempids link)
        result @(d/transact conn linked-data)
        realid-map (:tempids result)]
    (reduce (fn [a k] (assoc a k (d/resolve-tempid (d/db conn) realid-map (k a)))) assigned-tempids (keys assigned-tempids))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seeder
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Seeder [seed-data seed-result]
  component/Lifecycle
  (start [this]
    (let [dbs-to-seed (keys seed-data)
          tid-maps (reduce (fn [acc db-name]
                             (let [sd (get seed-data db-name)
                                   db (get this db-name)
                                   conn (.get-connection db)
                                   tempid-map (link-and-load-seed-data conn sd)]
                               (assoc acc db-name tempid-map)))
                           {} dbs-to-seed)
          pairwise-disjoint? (fn [maps]
                               (if (< (count maps) 2)
                                 true
                                 (let [all-keys (map (comp set keys) maps)
                                       pairs (combo/combinations all-keys 2)
                                       empty-pair? (fn [[ks1 ks2]]
                                                     (empty? (clojure.set/intersection ks1 ks2)))]
                                   (every? empty-pair? pairs))))]
      (assoc this :seed-result (if-not (pairwise-disjoint? (vals tid-maps))
                                 :disjoint
                                 tid-maps))))
  (stop [this]
    ;; You can't stop the seeder!
    this))

(defn make-seeder [seed-data]
  (component/using
    (map->Seeder {:seed-data seed-data})
    (vec (keys seed-data))))
