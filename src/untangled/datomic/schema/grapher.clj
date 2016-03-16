(ns untangled.datomic.schema.grapher
  (:require [datomic.api :as d]
            [datomic-schema-grapher.dot :as dot]
            [untangled.datomic.schema.dumper :as dumper]))

(defn user-schema-for-grapher
  "Returns seq of entities, with blacklist applied.

  Compatibility layer between `datomic-schema-grapher`
  and `untangled.datomic.schema`.
  Ditches enums; they lack `:db/valueType` which messes with grapher.
  "
  [db]
  (->> (dumper/get-user-schema db)
       (map first)
       (map (partial d/entity db))
       (filter :db/valueType)))

(defn user-references-for-grapher
  "Returns a seq of triples: (src-attr dst-table one-or-many).

  Compatibility layer between `datomic-schema-grapher`
  and `untangled.datomic.schema`.
  Relies on `:constraint/references`.
  "
  [db]
  (let [schema (user-schema-for-grapher db)
        ref-attrs (->> schema
                       (group-by :db/valueType)
                       :db.type/ref)]
    (->> ref-attrs
         (filter :constraint/references)
         (map (fn [ref-attr]
                (let [src (:db/ident ref-attr)
                      dst (:constraint/references ref-attr)
                      cardinality (name (:db/cardinality ref-attr))]
                  [src (namespace dst) cardinality]))))))

(defn spit-graph
  "Writes graph of `db` to `save-as` in DOT format.

  Required Positional Arguments:
  - db          : a `datomic.api/db` db
  - save-as     : string of path to output DOT file

  Synopsis:
    (grapher/spit-graph (d/db conn) \"auth-schema.dot\")

  Notes: If you have GraphViz installed locally, you can convert your
         DOT file to various formats on the command line:
         ```
           $ dot \"auth-schema.dot\" -Tpng -o auth-schema.png
           $ dot \"auth-schema.dot\" -Tpdf -o auth-schema.pdf
         ```
  "
  [db save-as]
  (let [schema (user-schema-for-grapher db)
        references (user-references-for-grapher db)]
    (spit save-as (dot/to-dot schema references))))
