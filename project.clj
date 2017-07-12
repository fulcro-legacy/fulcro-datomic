(defproject fulcrologic/fulcro-datomic "0.4.11"
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
                 [io.rkn/conformity "0.3.7" :exclusions [com.datomic/datomic-free]]
                 [com.taoensso/timbre "4.10.0"]
                 [commons-codec "1.10"]
                 [ring/ring "1.6.1" :scope "test"]          ; TODO: Remove this when spec gets deps right!
                 [fulcrologic/fulcro-spec "1.0.0-beta2" :scope "test" :exclusions [io.aviso/pretty]]
                 [fulcrologic/fulcro "1.0.0-beta2" :scope "test"] ; TODO: Remove when spec has correct deps
                 [org.clojure/clojurescript "1.9.671" :scope "test"]
                 [democracyworks/datomic-toolbox "2.0.4" :exclusions [com.datomic/datomic-pro commons-logging commons-codec]]
                 [org.clojure/tools.reader "1.0.0-beta4"]
                 [datomic-schema-grapher "0.0.1"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.20.0"]]

  :test-refresh {:report       fulcro-spec.reporters.terminal/fulcro-report
                 :with-repl    true
                 :changes-only true}

  :source-paths ["src"]
  :test-paths ["specs"]
  :resource-paths ["src" "resources"])
