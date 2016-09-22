(ns untangled.datomic.schema
  (:require
    [datomic.function :as df]
    [io.rkn.conformity :as conformity]
    [untangled.datomic.impl.util :as n]
    [taoensso.timbre :as timbre]
    [clojure.tools.namespace.find :refer [find-namespaces]]
    [clojure.java.classpath :refer [classpath]]
    [clojure.set :as set]
    [datomic.api :as datomic]
    [clojure.string :as str]))

;; The main schema functions
(defn fields* [fielddefs]
  (let [extract-type-and-options
        (fn [a [nm tp & opts]]
          (let [pure-opts (set (filter (some-fn vector? keyword? string?) opts))
                custom-opts (first (filter map? opts))
                options (cond-> [tp pure-opts]
                          custom-opts (conj custom-opts))]
            (assoc a (name nm) options)))]
    {:fields (reduce extract-type-and-options {} fielddefs)}))

(defmacro fields
  "Simply a helper for converting (fields [name :string :indexed]) into {:fields {\"name\" [:string #{:indexed}]}}"
  [& fielddefs]
  (fields* fielddefs))

(defn schema*
  "Simply merges several maps into a single schema definition and add one or two helper properties"
  [name maps]
  (apply merge
    {:name name :basetype (keyword name) :namespace name}
    maps))

(defmacro schema
  [nm & maps]
  `(schema* ~(name nm) [~@maps]))

(defn part
  [nm]
  (keyword "db.part" nm))

(declare ensure-entities-conform ensure-constraints-conform)

(defn run-core-schema [conn]
  (timbre/info "Applying core schema to database.")
  (doseq []
    (ensure-constraints-conform conn)
    (ensure-entities-conform conn)))

;; The datomic schema conversion functions
(defn get-enums [basens part enums]
  (map (fn [n]
         (let [nm (if (string? n) (.replaceAll (.toLowerCase n) " " "-") (name n))]
           [:db/add (datomic/tempid part) :db/ident (keyword basens nm)])) enums))

(def unique-mapping
  {:db.unique/value    :db.unique/value
   :db.unique/identity :db.unique/identity
   :unique-value       :db.unique/value
   :unique-identity    :db.unique/identity})

(defn field->datomic [basename part {:keys [gen-all? index-all?]} acc [fieldname [type opts custom]]]
  (let [uniq (first (remove nil? (map #(unique-mapping %) opts)))
        dbtype (keyword "db.type" (if (= type :enum) "ref" (name type)))
        result
        (cond->
          {:db.install/_attribute :db.part/db
           :db/id                 (datomic/tempid :db.part/db)
           :db/ident              (keyword basename fieldname)
           :db/valueType          dbtype
           :db/cardinality        (if (opts :many) :db.cardinality/many :db.cardinality/one)}
          (or index-all? gen-all? (opts :indexed))
          (assoc :db/index (boolean (or index-all? (opts :indexed))))

          (or gen-all? (seq (filter string? opts)))
          (assoc :db/doc (or (first (filter string? opts)) ""))
          (or gen-all? (opts :fulltext)) (assoc :db/fulltext (boolean (opts :fulltext)))
          (or gen-all? (opts :component)) (assoc :db/isComponent (boolean (opts :component)))
          (or gen-all? (opts :nohistory)) (assoc :db/noHistory (boolean (opts :nohistory)))

          (:references custom) (assoc :constraint/references (:references custom))
          (:with-values custom) (assoc :constraint/with-values (:with-values custom))
          (opts :definitive) (assoc :constraint/definitive (boolean (opts :definitive)))
          (opts :unpublished) (assoc :constraint/unpublished (boolean (opts :unpublished)))
          )]
    (concat
      acc
      [(if uniq (assoc result :db/unique uniq) result)]
      (if (= type :enum) (get-enums (str basename "." fieldname) part (first (filter vector? opts)))))))

(defn schema->datomic [opts acc schema]
  (if (or (:db/id schema) (vector? schema))
    (conj acc schema)                                       ;; This must be a raw schema definition
    (let [key (:namespace schema)
          part (or (:part schema) :db.part/user)]
      (reduce (partial field->datomic key part opts) acc (:fields schema)))))

(defn part->datomic [acc part]
  (conj acc
    {:db/id                 (datomic/tempid :db.part/db),
     :db/ident              part
     :db.install/_partition :db.part/db}))

(defn generate-parts [partlist]
  (reduce (partial part->datomic) [] partlist))

(defn generate-schema
  ([schema] (generate-schema schema {:gen-all? true}))
  ([schema {:keys [gen-all? index-all?] :as opts}]
   (reduce (partial schema->datomic opts) [] schema)))

(defn map->schema [sch]
  (generate-schema
    (reduce (fn [acc [sch-ns fields]]
              (->> fields
                (mapv (fn [[fname fval]] (cons fname fval)))
                (fields*)
                (schema* (name sch-ns))
                (conj acc)))
      [] sch)))

(defmacro with-require
  "A macro to be used with dbfn in order to add 'require'
  list to the function for using external libraries within
  database functions. For example:


       (with-require [datahub.validation [clojure.string :as s]]
         (dbfn ...))
  "
  [requires db-function]
  `(assoc-in ~db-function [:db/fn :requires] '~requires)
  )

(defmacro dbfn
  [name params partition & code]
  `{:db/id    (datomic.api/tempid ~partition)
    :db/ident ~(keyword name)
    :db/fn    (df/construct
                {:lang   "clojure"
                 :params '~params
                 :code   '~@code})})

(defmacro defdbfn
  "Define a datomic database function. All calls to datomic api's should be namespaced with datomic.api/ and you cannot use your own namespaces (since the function runs inside datomic)

  This defines a locally namespaced function as well - which is useful for testing.

  Your first parameter needs to always be 'db'.

  You'll need to commit the actual function's meta into your datomic instance by calling (d/transact (meta myfn))"
  [name params partition & code]
  `(def ~name
     (with-meta
       (fn ~name [~@params]
         ~@code)
       {:tx (dbfn ~name ~params ~partition ~@code)})))

(defn dbfns->datomic [& dbfn]
  (map (comp :tx meta) dbfn))


(declare dump-schema)

;; need this for testing..fatal is a non-mockable macro
(defn contains-lists? [l]
  (every? sequential? l))

(defn migrate-with
  "Use in a migration file to designate a function of one argument (the db connection) to be called AFTER the successful
  database migration defined by `tx`."
  [data-fn tx]
  (with-meta tx {:migrate-data data-fn}))

(defn all-migrations* [migration-namespace]
  "Obtain all of the migrations from a given base namespace string (e.g. \"datahub.migrations\").
  This is not memoized/cached, perfomance will suffer, see all-migrations for the 'faster' version"
  (let [migration-keyword
        #(keyword (str/replace (n/namespace-name %) #"^(.+)\.([^.]+)$" "$1/$2"))

        migration?
        #(and (.startsWith (n/namespace-name %) migration-namespace)
          (not (.endsWith (n/namespace-name %) ".template")))

        migration-spaces
        (filter migration? (n/load-namespaces migration-namespace))

        transactions
        (fn [nspace]
          (if-let [tfunc (ns-resolve nspace 'transactions)]
            (tfunc)
            (do
              (timbre/fatal "Missing 'transactions' function in " (n/namespace-name nspace))
              nil)))

        entry
        (fn [nspace]
          (if-let [txn (transactions nspace)]
            (if (contains-lists? txn)
              (let [data-fn (meta txn)]
                (vector (migration-keyword nspace) (merge {:txes txn} data-fn)))
              (do
                (timbre/fatal "Transaction function failed to return a list of transactions!" nspace)
                []))
            []))

        dated-kw-compare
        (fn [a b]
          (.compareTo
            (or (re-find #"\d{8,}" (name a)) (name a))
            (or (re-find #"\d{8,}" (name b)) (name b))))]
    (for [mig (into (sorted-map-by dated-kw-compare)
                (->> migration-spaces
                  (map entry)
                  ; eliminate empty items
                  (filter seq)))]
      (into {} [mig]))))
(def ^{:doc "memoized (cached) version of all-migrations*
            beware as changes to your migrations will not be reflected unless:
            - you've set the env var below to disable caching
            - you've restarted your repl/test-refresh/jvm/etc"}
all-migrations
  (if (#{"0" "false"} (System/getenv "UNTANGLED_DATOMIC_CACHE_MIGRATIONS"))
    all-migrations*
    (do (timbre/warn "Caching migrations, set env var UNTANGLED_DATOMIC_CACHE_MIGRATIONS to 0 or false to disable.")
        (memoize all-migrations*))))

(defn migrate
  "
  # (migrate connection namespace)

  Run all migrations.

  ## Parameters
  * `dbconnection` A connection to a datomic database
  * `nspace` The namespace name that contains the migrations.

  ## Examples

  (migrate conn \"datahub.migrations\")
  "
  [dbconnection nspace]
  (let [migrations (all-migrations nspace)]
    (timbre/info "Running migrations for" nspace)
    (doseq [migration migrations
            nm (keys migration)]
      (if (conformity/conforms-to? (datomic/db dbconnection) nm)
        (timbre/info nm "has already been applied to the database.")
        (try
          (timbre/info "Conforming " nm)
          (timbre/trace migration)
          (conformity/ensure-conforms dbconnection migration)
          (when-let [data-fn (get-in migration [nm :migrate-data])]
            (data-fn dbconnection))
          (if (conformity/conforms-to? (datomic/db dbconnection) nm)
            (timbre/info "Verified that database conforms to migration" nm)
            (timbre/error "Migration NOT successfully applied: " nm))
          (catch Exception e (timbre/fatal "migration failed" e)))))
    (let [schema-dump (into [] (dump-schema (datomic/db dbconnection)))]
      (timbre/trace "Schema is now" schema-dump)
      schema-dump)))

(defn check-migration-conformity [connection migrations verbose]
  (reduce (fn [nonconforming-migrations mig]
            (let [[migration] (keys mig)]
              (if-not (conformity/conforms-to? (datomic/db connection) migration)
                (conj nonconforming-migrations (if verbose mig migration))
                nonconforming-migrations)))
    #{} migrations))

(defn migrate-all [db-configs]
  (doseq [[_ config] db-configs]
    (let [{:keys [url schema]} config
          connection (datomic/connect url)]
      (run-core-schema connection)
      (migrate connection schema))))

(defn migration-status-all [db-configs verbose]
  (reduce
    (fn [acc config]
      (let [{:keys [url schema]} config
            connection (datomic/connect url)
            migrations (all-migrations* schema)]
        (into acc (check-migration-conformity connection migrations verbose))))
    #{} (vals db-configs)))


(defn dump-schema
  "Show the non-system attributes of the schema on the supplied datomic database."
  [db]
  (let [system-ns #{"confirmity"
                    "db"
                    "db.alter"
                    "db.bootstrap"
                    "db.cardinality"
                    "db.excise"
                    "db.fn"
                    "db.install"
                    "db.lang"
                    "db.part"
                    "db.type"
                    "db.unique"
                    "fressian"}
        idents (datomic/q '[:find [?ident ...]
                            :in $ ?system-ns
                            :where
                            [_ :db/ident ?ident]
                            [(namespace ?ident) ?ns]
                            [((comp not contains?) ?system-ns ?ns)]]
                 db system-ns)]
    (map #(datomic/touch (datomic/entity db %)) idents)))

(defn dump-entity
  "Dump an entity definition for an entity in a database.

  Parameters:
  * `database` : A datomic database
  * `entity-name`: The (string) name of the entity of interest.

  Returns the attributes of the supplied datomic database that are qualified by the given entity name"
  [db entity]
  (let [idents (datomic/q '[:find [?ident ...]
                            :in $ ?nm
                            :where
                            [_ :db/ident ?ident]
                            [(namespace ?ident) ?ns]
                            [(= ?nm ?ns)]]
                 db entity)]
    (map #(datomic/touch (datomic/entity db %)) idents)))

(defn entity-extensions
  "
  Returns the datoms necessary to create entity extensions for documetation and foreign attributes in the schema of
  the named entity.

  Parameters:
  * `name` : The entity name (as a keyword or string)
  * `doc` : The doc string to include for the entity.
  * `foreign-attributes` : A set (or list) of namespace-qualified attributes to consider legal foreign attributes on the
  named entity.

  "
  [name doc foreign-attributes]
  (let [name (keyword name)
        refs (map (fn [v]
                    [:db/ident v])
               (set foreign-attributes))
        basic {:db/id       (datomic/tempid :db.part/user)
               :entity/name name :entity/doc doc}
        trx (if (empty? refs)
              basic
              (conj basic {:entity/foreign-attribute refs}))]
    (list trx)))


;; defines the constraints schema and ensures that the database has these defined
(defn ensure-constraints-conform [conn]
  (let [schema (generate-schema
                 [
                  (schema constraint
                    (fields
                      [references :keyword]
                      [with-values :keyword :many]
                      [definitive :boolean]
                      [unpublished :boolean]
                      ))
                  ;; Database function which will throw an exception if the given argument does not match the
                  ;; current database's tranasction number (for optimistic concurrency control on validations)
                  (dbfn ensure-version [db version] :db.part/user
                    (if (not= version (datomic.api/basis-t db))
                      (throw (ex-info "Transactor version of database does not match required version" {}))
                      ))
                  ;; Database function that does referential integrity checks within the transactor. Pass
                  ;; the transaction data to this function.
                  (with-require [[untangled.datomic.schema :as v]]
                    (dbfn constrained-transaction [db transaction] :db.part/user
                      (let [result (datomic.api/with db transaction)]
                        (untangled.datomic.schema/validate-transaction result false)
                        transaction
                        )
                      )
                    )

                  ])
        norms-map {:datahub/constraint-schema {:txes (vector schema)}}]
    (doseq []
      (conformity/ensure-conforms conn norms-map [:datahub/constraint-schema])
      (if (conformity/conforms-to? (datomic/db conn) :datahub/constraint-schema)
        (timbre/info "Verified that database conforms to constraints")
        (timbre/error "Database does NOT conform to contstraints")
        )
      )
    )
  )


;; defines the constraints schema and ensures that the database has these defined
(defn ensure-entities-conform [conn]
  (let [schema (generate-schema
                 [
                  (schema entity
                    (fields
                      [name :keyword :unique-identity]
                      [doc :string]
                      [foreign-attribute :ref :many]
                      ))
                  ])
        norms-map {:datahub/entity-schema {:txes (vector schema)}}]
    (doseq []
      (conformity/ensure-conforms conn norms-map [:datahub/entity-schema])
      (if (conformity/conforms-to? (datomic/db conn) :datahub/entity-schema)
        (timbre/info "Verified that database conforms to entities")
        (timbre/error "Database does NOT conform to entities")
        )
      )
    )
  )



(defn- get-entity-from-attribute [key]
  (keyword (subs (first (str/split (str key) #"/")) 1))
  )

(defn- map-schema-results [acc attribute]
  (let [entities (first acc)
        definitives (second acc)
        id (:db/ident attribute)
        entityid (get-entity-from-attribute id)
        values (cond-> {:db/valueType   (:db/valueType attribute)
                        :db/cardinality (:db/cardinality attribute)
                        }
                 (:db/doc attribute) (assoc :db/doc (:db/doc attribute))
                 (:db/entity-doc attribute) (assoc :db/entity-doc (:rest/entity-doc attribute))
                 (:constraint/definitive attribute) (assoc :constraint/definitive (:constraint/definitive attribute))
                 (:constraint/unpublished attribute) (assoc :constraint/unpublished (:constraint/unpublished attribute))
                 (:constraint/references attribute) (assoc :constraint/references (:constraint/references attribute))
                 (:db/fulltext attribute) (assoc :db/fulltext (:db/fulltext attribute))
                 (:db/index attribute) (assoc :db/index (:db/index attribute))
                 (:db/unique attribute) (assoc :db/unique (:db/unique attribute))
                 (:db/isComponent attribute) (assoc :db/isComponent (:db/isComponent attribute))
                 )
        currvalues (if (contains? entities entityid) (:attributes (entityid entities)) {})
        newvalues (merge {id values} currvalues)
        entities-update (assoc entities entityid {:attributes newvalues})
        definitives-update (if (:constraint/definitive attribute) (conj definitives id) definitives)]
    [entities-update definitives-update]
    )
  )

; given a datomic-database and options build the schema for each type of entities described in the options
(defn- build-entity-representations [db]
  (let [partition (datomic/q '[:find ?e . :where [?e :db/ident :db.part/db]] db)
        schema (dump-schema db)
        attributes (get (group-by #(datomic/part (:db/id %)) schema) partition)
        map-results (reduce map-schema-results [{} []] attributes)]
    map-results
    )
  )

(defn- resolve-foreign-key [keys all-entity-values]
  (let [entity (first keys)
        attribute (second keys)]
    {attribute (-> all-entity-values entity :attributes attribute)}
    ))

; add the foreign attributes to the attribute map
(defn- add-foreign-atrributes [entity-key all-entity-values foreign-attributes]
  (let [entity-values (entity-key all-entity-values)]
    (if (> (count foreign-attributes) 0)
      (let [attributes (:attributes entity-values)
            foreign-attributes-expanded (map (fn [key] [(get-entity-from-attribute key) key]) foreign-attributes)
            extended-attributes (into {} (map #(resolve-foreign-key % all-entity-values) foreign-attributes-expanded))
            combined-attributes (into {} [attributes extended-attributes])]
        (assoc-in entity-values [:attributes] combined-attributes)
        )
      entity-values
      )
    )
  )

(defn- append-foreign-attributes [db entities]
  (let [attrs (datomic/q '[:find ?name ?attr :where [?e :entity/name ?name] [?e :entity/foreign-attribute ?f] [?f :db/ident ?attr]] db)
        foreign-attributes (reduce (fn [acc a]
                                     (let [k (first a)
                                           c (if (nil? (k acc)) [] (k acc))
                                           n (conj c (second a))]
                                       (assoc acc k n))) {} attrs)
        ]
    (reduce (fn [acc entity-key]
              (assoc acc entity-key (add-foreign-atrributes entity-key entities (entity-key foreign-attributes)))) {} (keys entities))
    )
  )

(defn- add-entity-extensions [db entities]
  (let [docs (into {} (datomic/q '[:find ?name ?doc :where [?e :entity/doc ?doc] [?e :entity/name ?name]] db))
        entities-with-docs (into {}
                             (map (fn [e]
                                    (let [doc (if (nil? ((first e) docs)) "" ((first e) docs))]
                                      {(first e) (assoc (second e) :doc doc)})) entities))
        entities-with-foreign-keys (append-foreign-attributes db entities-with-docs)]
    entities-with-foreign-keys
    )
  )

(defn fetch-schema [datomic-connection]
  "Retrieves the schema information from the database and constructs a map that organizes that schema informtion by
   entity type.

  Parameters:
  datomic-connection - An open connection to a datomic database

  Output: { :entities {:realm {:atttributes {:realm/name {:db/valueType    :db.type/string
                                                 :db/isComponent  false
                                                 :db/fulltext     false
                                                 :db/doc        \"Realm Name\"
                                                 :constraint/definitive true}}
                                             {:actor/system {:db/valueType    :db.type/string
                                                           :db/isComponent  false
                                                           :db/fulltext     false
                                                           :db/doc        \"Actor System\"
                                                           :constraint/definitive true}}
                                             {:actor/uuid {:db/valueType    :db.type/uuid
                                                           :db/isComponent  false
                                                           :db/fulltext     false
                                                           :db/doc        \"Actor System\"
                                                           :constraint/definitive true}}
                                          :doc \"entity-doc\" }}}"
  (let [datomic-database (datomic/db datomic-connection)
        map-results (build-entity-representations datomic-database)
        entites (apply dissoc (first map-results) [:constraint])
        definitive (second map-results)
        entities-with-attached-extensions (add-entity-extensions datomic-database entites)]
    {:entities entities-with-attached-extensions :definitive definitive}
    )
  )


; # Custom Validation of Datomic Transactions
;
; Datomic itself supports the following schema validations out of the box:
;
; * Correct field type
; * References (the only ON DELETE behavior is nullify)
;
; Our schema support adds the following (opt-in) validation enforcement:
;
; * Entities have a more definitive "kind", with a limited set of attributes defined by the schema.
; * References point to the right "kind" of entity. Normally, Datomic types are advisory (any attribute can go on
;   any entity. To enforce this we need to have a way to determine the "type" of an entity.
;
; The functions in this namespace implement these additions.
;
; The "implied" kind of an entity is the common namespace of a set of attributes (e.g. user). Since some
; arbitrary entity will have at least one of those properties when it is behaving as a user, we can
; derive the "kind" of an entity if we also declare which properties (e.g. :user/name) will *always*
; be on a "User". We call these attributes "definitive" (their presences define the "type" of the entity).
;
; Note that if you allow a foreign attribute  on an entity (e.g. :account/name on "user") and that
; foreign attribute is also definitive, then by placing both on an entity you're conferring that additional
; "type" on that entity (it is now an "account" and a "user"). This has additional validation implications
; (e.g. it can now be referred to by things that can refer to an account and a user). Conversely, removing
; such an attribute could cause validation to fail if such a removal would remove a "type" that other
; attributes or references rely on.
;
; ## Basic Operation
;
; The validation MUST be ACID compliant. Since the transactor is the only place that true ACID operations are possible,
; we must technically do some part of the validation there to ensure correctness. However, since the Datomic
; transactor is a limited resource (it is the single write thread for the entire database), we want to limit the
; overhead, and therefore adopt the following scheme for doing transactions:
;
; * First, do the full validation (correct attribute values, referential constraints correct) on the client. A
;   failure indicates no need to even *try* the real transaction. This also has the effect of pre-caching all the
;   relevant data in the peer.
; * Find the current database basis (transaction number)
; * Repeat the validation on the client/peer. The pre-cached data will make this attempt happen much more quickly than
; the first.
; * Attempt to apply a transaction *without validation*, but include a call to a database function the will throw an
; exception if the real database basis (transaction number) is different than what was read in step 2. If the transaction
; succeeds, then all is well since the database had not changed since the client validation.
; * IF the transaction fails, the database may be under too high of a write load for the optimistic approach above to
; succeed. So, instead apply the REFERENCE validations in the transactor. The attribute validations were already done
; on the client, so there is no need to repeat them in the transactor.
;
; This overall scheme is implemented by `vtransact`, which works the same as Datomic's `transact`, but does the validations
; as described above using additional data you associate with the schema.
;
; The transactor validations use support functions from this namespace, so this namespace MUST be on the transactor's
; CLASSPATH.
;
; Additionally, there are database functions you must install into your schema. See
; `untangled.datomic.impl.core-schema-definitions` for the code that installs the two transactor functions `ensure-version`
; and `constrained-transaction`.

(defn entity-has-attribute?
  "Returns true if the given entity has the given attribute in the given database.

  Parameters:
  `db` - The database to look the entity up in
  `entity-or-id` - The entity or an ID. If an entity, the db is NOT queried.
  `attr` - The attr to check

  Thus, if you pass an entity, then this function is identical to (contains? entity-or-id attr)
  "
  [db entity-or-id attr]
  (let [eid (or (:db/id entity-or-id) entity-or-id)
        result (datomic/q '[:find ?v .
                            :in $ ?e ?attr
                            :where [?e ?attr ?v]] db eid attr)]
    (boolean result)
    )
  )

(defn foreign-attributes
  "Get a list of the attributes that are *not* within the implied `kind` of entity, but which are still allowed to
  exist on that entity. Such attributes **must** be defined in the schema.

  Parameters:
  `db`: The database whose schema is checked
  `kind`: the keyword or string `kind` of entity to check.
  "
  [db kind]
  (set (datomic/q '[:find [?attr ...] :in $ ?kind
                    :where
                    [?e :entity/name ?kind]
                    [?e :entity/foreign-attribute ?f]
                    [?f :db/ident ?attr]] db kind))
  )

(defn core-attributes
  "
  Obtains the list of attributes that are within the namespace of `kind`. These are considered the *core*
   attributes of an entity of that kind. E.g. :user/name has `kind` user.

   Parameters:
   `db`: The database to check
   `kind`: The keyword or string name of the kind to examine.
  "
  [db kind]
  (set (datomic/q '[:find [?ident ...]
                    :in $ ?kind
                    :where
                    [_ :db/ident ?ident]
                    [(namespace ?ident) ?kind]
                    ]
         db (name kind)))
  )

(defn all-attributes
  "
  Derives the allowed attributes from the given database for a specific kind of entity.

  Parameters:
  `db` : The database to check
  `kind` : The kind (as a string or keyword)

  Returns a set of namespace-qualified keywords that define the allowed attributes for the given type, including
  foreign attributes.
  "
  [db kind]
  (clojure.set/union (core-attributes db kind) (foreign-attributes db kind))
  )

(defn reference-constraint-for-attribute
  "For a given attribute, finds referential constraints (if a ref, and given).

  Parameters:
  `db` - The database to derive schema from
  `attr` - The attribute of interest

  If found it returns the constraint as a map

      {
        :constraint/attribute  attr ; the attr you passed in
        :constraint/references target
        :constraint/with-values #{allowed-values ...}
      }

  otherwise nil."
  [db attr]
  (let [result (datomic/q '[:find (pull ?e [:constraint/references :constraint/with-values]) .
                            :in $ ?v
                            :where [?e :db/ident ?v] [?e :db/valueType :db.type/ref]] db attr)]
    (some-> (if (:constraint/with-values result)
              (assoc result :constraint/with-values (set (:constraint/with-values result)))
              result)
      (assoc :constraint/attribute attr)
      )
    )
  )

(defn- ensure-entity
  "Ensure that the specified eid is loaded as an entity.

  Parameters:
  `db` - The database to load from if the eid is NOT an entity
  `eid` - The entity or ID

  Returns the entity from the database, or eid if eid is already an entity.
  "
  [db eid]
  (if (instance? datomic.Entity eid)
    eid
    (datomic/entity db eid)
    )
  )

(defn entity-exists?
  "
  Query the database to see if the given entity ID exists in the database. This function is necessary because Datomic
  will *always* return an entity from `(datomic.api/entity *id*)`, which means you cannot test existence that way.

  Parameters:
  `conn-or-db` : A datomic connection *or* database. This allows the function to work on whichever is more convenient.
  `entity-id` : The numeric ID of the entity to test.
  "
  [conn-or-db entity-id]
  (let [db (if (= datomic.db.Db (type conn-or-db)) conn-or-db (datomic/db conn-or-db))]
    (-> (datomic/q '[:find ?eid :in $ ?eid :where [?eid]] db entity-id) seq boolean)
    )
  )

(defn entities-in-tx
  "Returns a set of entity IDs that were modified in the given transaction.

  Parameters:
  `tx-result` : The result of the transaction that was run (e.g. @(d/transact ...))
  "
  ([tx-result] (entities-in-tx tx-result false))
  ([tx-result include-deleted]
   (let [db (:db-after tx-result)
         tx (:tx-data tx-result)
         ids (set (->> tx (filter #(not= (.e %) (.tx %))) (map #(.e %))))
         ]
     (if include-deleted
       ids
       (set (filter #(entity-exists? db %) ids))
       )
     ))
  )

(defn entities-that-reference
  "Finds all of the entities that reference the given entity.

  Parameters:
  `db` : The database
  `e`  : The entity (or entity ID) that is referenced
  "
  [db e]
  (let [eid (if (instance? datomic.Entity e) (:db/id e) e)]
    (datomic/q '[:find [?e ...] :in $ ?eid :where [?e _ ?eid]] db eid)
    )
  )

(defn as-set
  "A quick helper function that ensures the given collection (or singular item) is a set.

  Parameters:
  `v`: A scalar, sequence, or set.

  Returns a set.
  "
  [v]
  (assert (not (map? v)) "as-set does not work on maps")
  (cond
    (seq? v) (set v)
    (vector? v) (set v)
    (set? v) v
    :else #{v}
    ))

(defn- is-reference-attribute-valid?
  "
  Determine, given the constraints on an attribute (which includes the targeted entity(ies)), if the given entity has
  a valid value for that attribute.

  Parameters:
  `db` - The database to validate against
  `entity` - The entity (or eid) to validate
  `constraint` - The database constraint to validate (as returned by reference-constraint-for-attribute)
  "
  [db entity constraint]
  (let [entity (ensure-entity db entity)
        source-attr (:constraint/attribute constraint)
        targetids (as-set (source-attr entity))
        target-attr (:constraint/references constraint)
        allowed-values (:constraint/with-values constraint)
        value-incorrect? (fn [entity attr allowed-values]
                           (let [value (attr entity)]
                             (not ((set allowed-values) value))
                             )
                           )
        error-msg (fn [msg entity attr target] {:source entity :reason msg :target-attr attr :target target})
        ]
    (some #(cond
            (not (entity-has-attribute? db % target-attr)) (error-msg "Target attribute is missing" entity target-attr %)
            (and allowed-values
              (value-incorrect? % target-attr allowed-values)) (error-msg "Target attribute has incorrect value"
                                                                 entity target-attr %)
            :else false)
      targetids)
    )
  )

(defn invalid-references
  "
  Returns all of the references *out* of the given entity that are invalid according to the schema's constraints. Returns
  nil if all references are valid.

  Parameters:
  `db` : The database that defines the schema
  `e` : The entity or entity ID to check
  "
  [db e]
  (let [entity (ensure-entity db e)
        attributes (keys entity)
        get-constraint (fn [attr] (reference-constraint-for-attribute db attr))
        drop-empty (partial filter identity)
        constraints-to-check (drop-empty (map get-constraint attributes))
        check-attribute (partial is-reference-attribute-valid? db entity)
        problems (drop-empty (map check-attribute constraints-to-check))
        ]
    (if (empty? problems) nil problems)
    ))



(defn definitive-attributes
  "
  Returns a set of attributes (e.g. :user/email) that define the 'kinds' of entities in the specified database.
  When such an attribute appears on an entity, it implies that entity is allowed to be treated as-if it has
  the 'kind' of that attribute's namespace (e.g. the presence of :user/email on an entity implies that
  the entity has kind 'user').

  Such attributes are marked as :definitive in the schema.
  "
  [db]
  (set (map :db/ident (datomic/q '[:find [(pull ?e [:db/ident]) ...]
                                   :where
                                   [?e :db/valueType _]
                                   [?e :constraint/definitive true]
                                   ] db)))
  )

(defn entity-types
  "Given a db and an entity, this function determines all of the types that the given entity conforms to (by scanning
  for attributes on the entity that have the :definitive markers in the schema.

  Parameters:
  `db` - The database (schema) to check against
  `e` - The Entity (or ID) to check
  "
  [db e] []
  (let [entity (ensure-entity db e)
        attrs (set (keys entity))
        type-markers (definitive-attributes db)
        type-keywords (set/intersection attrs type-markers)
        type-of (comp keyword namespace)
        ]
    (->> type-keywords (map type-of) (set))
    )
  )

(defn invalid-attributes
  "Check the given entity against the given database schema and determine if it has attributes that are not allowed
  according to the schema.

  Parameters:
  `db` : The database
  `entity` : The entity

  Returns nil if there are only valid attributes; otherwise returns a set of attributes that are present
  but are not allowed by the schema.
  "
  [db entity]
  (let [types (entity-types db entity)
        valid-attrs (set (mapcat (partial all-attributes db) types))
        current-attrs (set (keys entity))
        invalid-attrs (set/difference current-attrs valid-attrs)
        ]
    (if (empty? invalid-attrs) nil invalid-attrs)
    )
  )

(defn validate-transaction
  "
  Examines a transaction result to determine if the changes are valid. Throws exceptions if there are problems.

  ## Validation Algorithm:

  1. Find all of the entitied that were modified.
  - Entities whose attribute set changed
  - Entities that were updated due to reference 'nulling'
  2. Ignore any entities that were removed
  3. For every modified entity E:
  - Find all of the entities R that refer to E and verify that the references R->E are still valid
  - Verify that all references out of E are still valid
  - Derive the types T using definitive-attributes for E
  - Find all valid attributes A for T using schema
  - Verify that E contains only those attributes in A

  Parameters:
  `tx-result` - The return value of datomic `transact`, `with`, or similar function.
  `validate-attributes?` - Should the attributes be checkd? Defaults to false. Do NOT do in transactor, since it is costly.

  This function returns true if the changes are valid; otherwise it throws exceptions. When run within the transactor
  an exception will cause the transacation to abort, and this function is used by the client **and** the transactor
  db function.

  The thrown transaction will include the message 'Invalid References' if the transaction is invalid due to referential
  constraints. The message will include 'Invalid Attributes' if the transaction is invalid due to attributes on
  entities that are not allowed (e.g. a realm attribute on a user type).
  "
  ([tx-result] (validate-transaction tx-result false))
  ([tx-result validate-attributes?]
   (let [db (:db-after tx-result)
         modified-entities (entities-in-tx tx-result)       ;; 1 & 2 all except removed
         referencing-entities (fn [e] (entities-that-reference db e))
         ]
     (doseq [E modified-entities]
       (let [E (ensure-entity db E)]
         (if-let [bad-attrs (and validate-attributes? (invalid-attributes db E))]
           (throw (ex-info "Invalid Attributes" {:problems bad-attrs})))
         (if-let [bad-refs (invalid-references db E)] (throw (ex-info "Invalid References" {:problems bad-refs})))
         (doseq [R (referencing-entities E)]
           (if-let [bad-refs (invalid-references db R)] (throw (ex-info "Invalid References" {:problems bad-refs})))
           )
         )
       )
     true
     )
    )
  )

(defn vtransact
  "
  Execute a transaction in datomic while enforcing schema validations. The internal algorithm of this function
  attempts full validation on the peer (client). If that validation succeeds, then it attempts to run the same
  transaction on the transactor but only if the version of the database has not changed (optimistic concurrency).

  If the database *has* changed, then this function requests that the transactor do just the reference validations (the
  earlier validation will be ok for 'allowed attributes').

  The supporting transactor functions are in core_schema_definitions.clj.

  This function returns exactly what datomic.api/transact does, or throws an exception if the transaction cannot
  be applied.
  "
  [connection tx-data]
  (let [db-ignored (datomic/db connection)
        _ (datomic/with db-ignored tx-data)                 ;; this is a pre-caching attempt
        db (datomic/db connection)
        version (datomic/basis-t db)
        optimistic-result (datomic/with db tx-data)         ; the 'real' attempt
        version-enforced-tx (cons [:ensure-version version] tx-data)
        reference-checked-tx [[:constrained-transaction tx-data]]
        ]
    (try
      (validate-transaction optimistic-result true)         ;; throws if something is wrong
      (try
        (let [result (datomic/transact connection version-enforced-tx)]
          @result                                           ;; evaluate the future...will throw if there was a problem
          result
          )
        (catch Exception e
          (datomic/transact connection reference-checked-tx)
          )
        )
      (catch Exception e
        (future (throw e))
        )
      )
    )
  )

(defn refcas* [db eid rel old-value new-value retract-component?]
  (let [entity (datomic.api/entity db eid)
        relation (datomic.api/entity db rel)
        ->set (fn [maybe-set]
                (cond
                  (instance? java.util.Collection maybe-set) (set maybe-set)
                  (nil? maybe-set) #{}
                  :else #{maybe-set}))
        old-values (->set old-value)
        new-values (->set new-value)
        existing-values (set (map :db/id (->set (get entity rel))))]
    (when-not (= old-values existing-values)
      (throw (java.util.ConcurrentModificationException.
               (str "old-values do not match existing values: "
                 "Info: "
                 "expected: " (pr-str old-values) "; "
                 "actual: " (pr-str existing-values) "; "
                 "coll? " (instance? java.util.Collection old-value) "; "
                 "nil? " (nil? old-value) "; "
                 "type: " (type old-value)))))

    (concat
      (for [val existing-values
            :when (not (contains? new-values val))]
        ; FIXME: Why is this necessary?
        (if (and retract-component? (:db/isComponent relation))
          ;; TODO: decide if using retractEntity or retract
          [:db.fn/retractEntity val]
          [:db/retract eid rel val]))
      (for [val new-values
            :when (not (contains? old-values val))]
        {:db/id eid
         rel    val}))))

;; Used for compare-and-set on ref fields. If an id in `old-value` is not present in `new-value`, it will
;; be removed from the provided entity's attribute AND it will be retracted from the database.
(defdbfn refcas [db eid rel old-value new-value] :db.part/user
  (untangled.datomic.schema/refcas* db eid rel old-value new-value true))

;; Same as refcas above, but will NOT retract ids removed in new-value from the entire databse. Instead, it will
;; just retract the reference on the provided field.
(defdbfn refcas-remove [db eid rel old-value new-value] :db.part/user
  (untangled.datomic.schema/refcas* db eid rel old-value new-value false))
