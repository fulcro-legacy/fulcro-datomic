(ns untangled.datomic.impl.util-spec
  (:require [untangled-spec.core :refer [specification assertions]]
            [untangled.datomic.impl.util :as util]
            [taoensso.timbre :refer [debug info fatal error]]
            [clojure.tools.namespace.find :refer [find-namespaces]]
            [clojure.java.classpath :refer [classpath]]))

(specification "load-namespaces"
  (assertions
    "processes namespaces that start with a given prefix"
    (util/load-namespaces "resources.load-namespaces") =fn=> #(every? (fn [x] (.startsWith (str x) "resources.load-namespace")) %)
    "returns the symbols of namespaces that were loaded"
    (util/load-namespaces "resources.load-namespace") => ['resources.load-namespace.load-namespaces]
    "will not accept a partial package name as a prefix"
    (util/load-namespaces "resources.load-namespac") => []))

(specification "single-arg"
  (assertions
    "keeps truthy values"
    (util/single-arg {:a nil :b false :c nil}) => nil
    (util/single-arg {:a true :b false :c nil}) => '([:a true])
    (util/single-arg {:a "string" :b false :c nil}) => '([:a "string"])
    (util/single-arg {:a 42 :b false :c nil}) => '([:a 42])
    "returns nil when given more than one truthy value"
    (util/single-arg {:a "string" :b 42 :c nil}) => nil))