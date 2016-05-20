(ns untangled.datomic.test-helpers-spec
  (:require
    [datomic.api :as d]
    [com.stuartsierra.component :as component]
    [untangled.datomic.test-helpers :as test-helpers]
    [untangled-spec.core :refer [specification
                                 assertions
                                 when-mocking
                                 component
                                 behavior]]
    [clojure.test :refer :all]))

(specification "Temporary ID"
  (behavior "tempid keywords are any keywords in namespaces starting with tempid"
    (assertions
      (test-helpers/is-tempid-keyword? :datomic.id/blah) => true
      (test-helpers/is-tempid-keyword? :datomic.id.other/blah) => true
      (test-helpers/is-tempid-keyword? :datomic.id.a.b.c/dude) => true
      (test-helpers/is-tempid-keyword? :other.tempid/blah) => false)))

(specification "Assigning a temp ID"
  (behavior "Creates a new tempid for a list datom"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {:datomic.id/a 1} [:db/add :datomic.id/b :a/boo "hello"])
        => {:datomic.id/a 1
            :datomic.id/b :..id..})))

  (behavior "Tolerates an existing tempid for a list datom"
    (assertions
      (test-helpers/assign-temp-id {:datomic.id/a 1} [:db/add :datomic.id/a :a/boo "hello"])
      => {:datomic.id/a 1}))

  (behavior "Refuses to assign an id if the same ID is already in the id map"
    (assertions
      (test-helpers/assign-temp-id {:datomic.id/a 1} {:db/id :datomic.id/a :a/boo "hello"})
      =throws=> (AssertionError #"Entity uses a duplicate ID")))

  (behavior "Includes the entity's metadata in the duplicate ID message"
    (assertions
      (test-helpers/assign-temp-id {:datomic.id/a 1} ^{:line 4} {:db/id :datomic.id/a :a/boo "hello"})
      =throws=> (AssertionError #"duplicate ID.*line 4")))

  (behavior "returns the original map if the item has no ID field"
    (assertions
      (test-helpers/assign-temp-id :..old-ids.. {:a/boo "hello"}) => :..old-ids..))

  (behavior "Generates an ID and puts it in the ID map when a tempid field is present"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {} {:db/id :datomic.id/thing :a/boo "hello"})
        => {:datomic.id/thing :..id..})))

  (behavior "recognizes tempid namespaces that have sub-namespaces like tempid.boo"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {} {:db/id :datomic.id.boo/thing :a/boo "hello"})
        => {:datomic.id.boo/thing :..id..})))
  (behavior "Handles tempid assignment in recursive maps"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {} {:db/id :datomic.id.boo/thing :nested-thing {:db/id :datomic.id/blah :a/boo "hello"}})
        => {:datomic.id.boo/thing :..id.. :datomic.id/blah :..id..}))
    ))

(specification "Assigning ids in an entity"
  (behavior "throws an AssertionError if a tempid keyword is referred to that is not in the ID map"
    (assertions
      (test-helpers/assign-ids {:datomic.id/thing 22 :datomic.id/other 42}
        ^{:line 33} {:other/thing :datomic.id/blah :user/name "Tony"})
      =throws=> (AssertionError #"Missing.*datomic.id/blah.*line 33")))

  (behavior "replaces tempids with values from the idmap in scalar values"
    (assertions
      (test-helpers/assign-ids {:datomic.id/thing 22 :datomic.id/other 42}
        {:other/thing :datomic.id/thing :user/name "Tony"})
      => {:other/thing 22 :user/name "Tony"}))

  (behavior "replaces tempids with values from the idmap in vector values"
    (assertions
      (test-helpers/assign-ids {:datomic.id/thing 22 :datomic.id/other 42}
        {:other/thing [:datomic.id/thing :datomic.id/other]
         :user/name   "Tony"})
      => {:other/thing [22 42] :user/name "Tony"}))

  (behavior "replaces tempids with values from the idmap in set values"
    (assertions
      (test-helpers/assign-ids {:datomic.id/thing 22 :datomic.id/other 42}
        {:other/thing #{:datomic.id/thing :datomic.id/other}
         :user/name   "Tony"})
      => {:other/thing #{22 42} :user/name "Tony"}))

  (behavior "replaces temporary ID of datomic list datom with calculated tempid"
    (assertions
      (test-helpers/assign-ids {:datomic.id/thing 1} [:db/add :datomic.id/thing :user/name "tony"])
      => [:db/add 1 :user/name "tony"]))

  (behavior "replaces temporary IDs in lists within datomic list datom"
    (assertions
      (test-helpers/assign-ids {:datomic.id/thing 1 :datomic.id/that 2}
        [:db/add :..id.. :user/parent [:datomic.id/that]])
      => [:db/add :..id.. :user/parent [2]]))

  (behavior "throws an AssertionError if the idmap does not contain the id"
    (assertions
      ;; force evaluation of assign-ids with (into ...)
      (into {} (test-helpers/assign-ids {} [:db/add :datomic.id/this :user/parent :datomic.id/that])) =throws=> (AssertionError #"Missing ID :datomic.id/this")
      (test-helpers/assign-ids {} {:other/thing #{:datomic.id/thing :datomic.id/other} :user/name "Tony"}) =throws=> (AssertionError #"Missing ID :datomic.id/thing"))))

(specification "linking entities"
  (behavior "does not accept a map as an argument"
    (assertions
      (test-helpers/link-entities {:k :v})
      =throws=> (AssertionError #"Argument must be a"))))

(specification "setting up a db fixture"
  (behavior "does NOT change the outside context's timbre's log level"
    (let [old-log-level (:level taoensso.timbre/*config*)
          mock-seed-fn (fn [conn] (test-helpers/link-and-load-seed-data conn []))]
      (test-helpers/with-db-fixture
        db (assertions
             (:level taoensso.timbre/*config*) => :warn)
        :seed-fn mock-seed-fn
        :migrations "resources.datomic-schema.validation-schema"
        :log-level :warn)
      (assertions
        (:level taoensso.timbre/*config*) => old-log-level))))

(specification "the seeder component"
  (behavior "if the seed-data's tx-data's have overlapping tempids it should return :disjoint"
    (test-helpers/with-db-fixture
      db (test-helpers/with-db-fixture
           db2 (let [seeder (test-helpers/make-seeder {:db  [{:db/id :datomic.id/thing}]
                                                       :db2 [{:db/id :datomic.id/thing}]})
                     system (component/system-map
                              :logger {}
                              :db db
                              :db2 db2
                              :seeder seeder)
                     started-system (.start system)]
                 (assertions
                   (get-in started-system [:seeder :seed-result])
                   => :disjoint))
           :migrations "resources.datomic-schema.validation-schema")
      :migrations "resources.datomic-schema.validation-schema"))

  (behavior "otherwise it should return {:db-name {:tid :rid}}"
    (test-helpers/with-db-fixture
      db (test-helpers/with-db-fixture
           db2 (let [seeder (test-helpers/make-seeder {:db  [{:db/id :datomic.id/thing}]
                                                       :db2 [{:db/id :datomic.id/thing2}]})
                     system (component/system-map
                              :logger {}
                              :db db
                              :db2 db2
                              :seeder seeder)
                     started-system (.start system)]
                 (assertions
                   (get-in started-system [:seeder :seed-result])
                   => {:db {:datomic.id/thing nil}
                       :db2 {:datomic.id/thing2 nil}}))
           :migrations "resources.datomic-schema.validation-schema")
      :migrations "resources.datomic-schema.validation-schema")))
