(defproject fulcrologic/fulcro-datomic "1.0.0-SNAPSHOT"
  :description "Datomic plugin for Fulcro webapps"
  :url "http://www.github.com/fulcro-web"
  :min-lein-version "2.7.0"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                 [datomic-helpers "1.0.0"]
                 [com.datomic/datomic-free "0.9.5561" :scope "provided" :exclusions [joda-time]]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.clojure/tools.namespace "0.3.0-alpha4"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/java.classpath "0.2.3"]
                 [io.rkn/conformity "0.3.2" :exclusions [com.datomic/datomic-free]]
                 [com.taoensso/timbre "4.10.0"]
                 [commons-codec "1.10"]
                 [org.clojure/tools.reader "1.0.0"]
                 [fulcrologic/fulcro-spec "1.0.0-beta3-SNAPSHOT" :scope "test" :exclusions [io.aviso/pretty com.google.guava/guava joda-time]]
                 [democracyworks/datomic-toolbox "2.0.4" :exclusions [com.datomic/datomic-pro commons-logging commons-codec]]
                 [datomic-schema-grapher "0.0.1"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.20.0"]]

  :test-refresh {:report       fulcro-spec.reporters.terminal/fulcro-report
                 :with-repl    true
                 :changes-only true}

  :source-paths ["src"]
  :test-paths ["specs"]
  :resource-paths ["src" "resources"])
