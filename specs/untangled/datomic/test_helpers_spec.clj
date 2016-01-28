(ns untangled.datomic.test-helpers-spec
  (:require
    [datomic.api :as d]
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
      (test-helpers/is-tempid-keyword? :tempid/blah) => true
      (test-helpers/is-tempid-keyword? :tempid.other/blah) => true
      (test-helpers/is-tempid-keyword? :tempid.a.b.c/dude) => true
      (test-helpers/is-tempid-keyword? :other.tempid/blah) => false)))

(specification "Assigning a temp ID"
  (behavior "Creates a new tempid for a list datom"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {:tempid/a 1} [:db/add :tempid/b :a/boo "hello"])
        => {:tempid/a 1
            :tempid/b :..id..})))

  (behavior "Tolerates an existing tempid for a list datom"
    (assertions
      (test-helpers/assign-temp-id {:tempid/a 1} [:db/add :tempid/a :a/boo "hello"])
      => {:tempid/a 1}))

  (behavior "Refuses to assign an id if the same ID is already in the id map"
    (assertions
      (test-helpers/assign-temp-id {:tempid/a 1} {:db/id :tempid/a :a/boo "hello"})
      =throws=> (AssertionError #"Entity uses a duplicate ID")))

  (behavior "Includes the entity's metadata in the duplicate ID message"
    (assertions
      (test-helpers/assign-temp-id {:tempid/a 1} ^{:line 4} {:db/id :tempid/a :a/boo "hello"})
      =throws=> (AssertionError #"duplicate ID.*line 4")))

  (behavior "returns the original map if the item has no ID field"
    (assertions
      (test-helpers/assign-temp-id :..old-ids.. {:a/boo "hello"}) => :..old-ids..))

  (behavior "Generates an ID and puts it in the ID map when a tempid field is present"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {} {:db/id :tempid/thing :a/boo "hello"})
        => {:tempid/thing :..id..})))

  (behavior "recognizes tempid namespaces that have sub-namespaces like tempid.boo"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {} {:db/id :tempid.boo/thing :a/boo "hello"})
        => {:tempid.boo/thing :..id..})))
  (behavior "Handles tempid assignment in recursive maps"
    (when-mocking
      (d/tempid :db.part/user) => :..id..

      (assertions
        (test-helpers/assign-temp-id {} {:db/id :tempid.boo/thing :nested-thing {:db/id :tempid/blah :a/boo "hello"}})
        => {:tempid.boo/thing :..id.. :tempid/blah :..id..}))
    ))

(specification "Assigning ids in an entity"
  (behavior "throws an AssertionError if a tempid keyword is referred to that is not in the ID map"
    (assertions
      (test-helpers/assign-ids {:tempid/thing 22 :tempid/other 42}
        ^{:line 33} {:other/thing :tempid/blah :user/name "Tony"})
      =throws=> (AssertionError #"Missing.*tempid/blah.*line 33")))

  (behavior "replaces tempids with values from the idmap in scalar values"
    (assertions
      (test-helpers/assign-ids {:tempid/thing 22 :tempid/other 42}
        {:other/thing :tempid/thing :user/name "Tony"})
      => {:other/thing 22 :user/name "Tony"}))

  (behavior "replaces tempids with values from the idmap in vector values"
    (assertions
      (test-helpers/assign-ids {:tempid/thing 22 :tempid/other 42}
        {:other/thing [:tempid/thing :tempid/other]
         :user/name   "Tony"})
      => {:other/thing [22 42] :user/name "Tony"}))

  (behavior "replaces tempids with values from the idmap in set values"
    (assertions
      (test-helpers/assign-ids {:tempid/thing 22 :tempid/other 42}
        {:other/thing #{:tempid/thing :tempid/other}
         :user/name   "Tony"})
      => {:other/thing #{22 42} :user/name "Tony"}))

  (behavior "replaces temporary ID of datomic list datom with calculated tempid"
    (assertions
      (test-helpers/assign-ids {:tempid/thing 1} [:db/add :tempid/thing :user/name "tony"])
      => [:db/add 1 :user/name "tony"]))

  (behavior "replaces temporary IDs in lists within datomic list datom"
    (assertions
      (test-helpers/assign-ids {:tempid/thing 1 :tempid/that 2}
        [:db/add :..id.. :user/parent [:tempid/that]])
      => [:db/add :..id.. :user/parent [2]]))

  (behavior "throws an AssertionError if the idmap does not contain the id"
    (assertions
      ;; force evaluation of assign-ids with (into ...)
      (into {} (test-helpers/assign-ids {} [:db/add :tempid/this :user/parent :tempid/that])) =throws=> (AssertionError #"Missing ID :tempid/this")
      (test-helpers/assign-ids {} {:other/thing #{:tempid/thing :tempid/other} :user/name "Tony"}) =throws=> (AssertionError #"Missing ID :tempid/thing"))))

(specification "linking entities"
  (behavior "does not accept a map as an argument"
    (assertions
      (test-helpers/link-entities {:k :v})
      =throws=> (AssertionError #"Argument must be a"))))

(specification "helper functions"
  (assertions
    "datomic-id->tempid"
    (test-helpers/datomic-id->tempid [{:id :datomic.id/asdf :foo :om.id/asdf} {:datomic.id/asdf :id}])
    => [{:id :tempid/asdf :foo :om.id/asdf} {:tempid/asdf :id}]))
