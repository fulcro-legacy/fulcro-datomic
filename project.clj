(defproject fulcrologic/fulcro-datomic "2.0.0-alpha1"
  :description "Datomic plugin for Fulcro webapps"
  :url "http://www.github.com/fulcro-web"
  :min-lein-version "2.7.0"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [datomic-helpers "1.0.0"]
                 [com.datomic/datomic-free "0.9.5561" :scope "provided" #_#_:exclusions [joda-time]]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.clojure/tools.namespace "0.3.0-alpha4"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/java.classpath "0.2.3"]
                 [io.rkn/conformity "0.5.1"]
                 [com.taoensso/timbre "4.10.0"]
                 [fulcrologic/fulcro-spec "2.0.0-beta3" :scope "test"]
                 [democracyworks/datomic-toolbox "2.0.4" :exclusions [com.datomic/datomic-pro]]
                 [datomic-schema-grapher "0.0.1"]

                 ; pinned versions. remove and re-calculate if changing versions of anything above
                 [commons-codec "1.10"]
                 [commons-logging "1.2"]
                 [com.google.guava/guava "20.0"]
                 [org.clojure/tools.reader "1.1.0"]
                 [joda-time "2.8.2"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.20.0"]]

  :test-refresh {:report       fulcro-spec.reporters.terminal/fulcro-report
                 :with-repl    true
                 :changes-only true}

  :source-paths ["src"]
  :test-paths ["specs"]
  :resource-paths ["src" "resources"])
