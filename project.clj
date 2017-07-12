(defproject fulcrologic/fulcro-datomic "0.4.11"
  :description "Datomic plugin for Fulcro webapps"
  :url "http://www.github.com/fulcro-web"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [datomic-helpers "1.0.0"]
                 [com.datomic/datomic-free "0.9.5344" :scope "provided" :exclusions [joda-time]]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [com.stuartsierra/component "0.3.1"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/java.classpath "0.2.2"]
                 [io.rkn/conformity "0.3.4" :exclusions [com.datomic/datomic-free]]
                 [com.taoensso/timbre "4.3.1"]
                 [fulcrologic/fulcro-spec "1.0.0-beta2" :scope "test" :exclusions [io.aviso/pretty]]
                 [democracyworks/datomic-toolbox "2.0.0" :exclusions [com.datomic/datomic-pro]]
                 [datomic-schema-grapher "0.0.1"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.14.0"]]

  :test-refresh {:report       fulcro-spec.reporters.terminal/fulcro-report
                 :with-repl    true
                 :changes-only true}

  :source-paths ["src"]
  :test-paths ["specs"]
  :resource-paths ["src" "resources"])
