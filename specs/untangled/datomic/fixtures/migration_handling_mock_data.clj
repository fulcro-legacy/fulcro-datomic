(ns untangled.datomic.fixtures.migration-handling-mock-data)

(def list-dbs-config {:survey        {:url       "datomic:mem://survey"
                                      :schema    "survey.migrations"
                                      :auto-drop false}
                      :some-other-db {:url       "datomic:mem://some-other-db"
                                      :schema    "some-other-db.migrations"
                                      :auto-drop false}})

(def migrations '({:survey.migrations/survey-20151106
                   {:txes
                    [({:db/index              false,
                       :db/valueType          :db.type/string,
                       :db/noHistory          false,
                       :db/isComponent        false,
                       :db.install/_attribute :db.part/db,
                       :db/fulltext           false,
                       :db/cardinality        :db.cardinality/one,
                       :db/doc                "Is this property awesome?",
                       :db/id                 {:part :db.part/db, :idx -1000509},
                       :db/ident              :property/awesome})]}}
                   {:survey.migrations/survey-20151109
                    {:txes
                     [({:db/index              false,
                        :db/valueType          :db.type/string,
                        :db/noHistory          false,
                        :db/isComponent        false,
                        :db.install/_attribute :db.part/db,
                        :db/fulltext           false,
                        :db/cardinality        :db.cardinality/one,
                        :db/doc                "Is this property better?",
                        :db/id                 {:part :db.part/db, :idx -1000510},
                        :db/ident              :property/better})]}}
                   {:survey.migrations/survey-20151110
                    {:txes
                     [({:db/index              false,
                        :db/valueType          :db.type/string,
                        :db/noHistory          false,
                        :db/isComponent        false,
                        :db.install/_attribute :db.part/db,
                        :db/fulltext           false,
                        :db/cardinality        :db.cardinality/one,
                        :db/doc                "Is this property hotter?",
                        :db/id                 {:part :db.part/db, :idx -1000511},
                        :db/ident              :property/hotter})]}}
                   {:survey.migrations/survey-20151111
                    {:txes
                     [({:db/index              false,
                        :db/valueType          :db.type/string,
                        :db/noHistory          false,
                        :db/isComponent        false,
                        :db.install/_attribute :db.part/db,
                        :db/fulltext           false,
                        :db/cardinality        :db.cardinality/one,
                        :db/doc                "Is this property funkier?",
                        :db/id                 {:part :db.part/db, :idx -1000512},
                        :db/ident              :property/funkier})]}}))
