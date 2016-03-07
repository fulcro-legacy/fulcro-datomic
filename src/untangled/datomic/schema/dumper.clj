(ns untangled.datomic.schema.dumper
  (:require [datomic.api :as d]
            [clojure.pprint :as pp]))

;; Blacklist of keyword namespaces to elide when looking at attrs.
(def system-ns #{                  ;; s/ => untangled.datomic.schema
                 "confirmity"      ;; conformity migration lib
                 "constraint"      ;; s/reference-constraint-for-attribute
                 "datomic-toolbox" ;; from lib of same name
                 "db"
                 "db.alter"
                 "db.bootstrap"
                 "db.cardinality"
                 "db.excise"
                 "db.fn"
                 "db.install"
                 "db.lang"
                 "db.part"
                 "db.sys"
                 "db.type"
                 "db.unique"
                 "entity"         ;; s/entity-extensions
                 "fressian"
                 })

(defn get-user-schema
  "Returns seq of [eid attr-kw] pairs."
  [db]
  (d/q '[:find ?e ?ident
         :in $ ?system-ns
         :where
         [?e :db/ident ?ident]
         [(namespace ?ident) ?ns]
         [((complement contains?) ?system-ns ?ns)]]
       db system-ns))

(defn eid->map [db eid]
  (->> eid
       (d/entity db)
       d/touch
       (into {:db/id eid})))

(defn infer-schema [db]
  (let [e->m (partial eid->map db)]
    (->> (get-user-schema db)
         (map first)
         sort
         (map e->m))))

(defn tidy-schema [schema]
  (->> schema
       (sort-by :db/id)
       (map (fn [m] (dissoc m :db/id)))
       (into [])))

(defn spit-schema [schema save-as]
  (let [result (tidy-schema schema)]
    (spit save-as (with-out-str (pp/pprint result)))))

(defn dump-schema
  "Returns vector of maps as current schema of `db`.

  Required Positional Arguments:
  - db        : a `datomic.api/db` db
  Optional Keyword Arguments:
  - :save-as  : path to output schema dump as EDN file

  Synopsis:
    ;; only returns seq of maps
    (dumper/dump-schema (d/db conn))
    ;; writes EDN-ified schema to :save-as path and returns seq of maps
    (dumper/dump-schema (d/db auth-conn) :save-as \"auth-schema-dump.edn\")
  "
  [db & {:keys [save-as]}]
  (let [result (infer-schema db)]
    (when save-as
      (spit-schema result save-as))
    result))
