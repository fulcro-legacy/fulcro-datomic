(defproject navis/untangled-datomic "0.4.0-SNAPSHOT"

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [datomic-helpers "1.0.0"]
                 [com.datomic/datomic-pro "0.9.5206" :exclusions [joda-time]]
                 [com.stuartsierra/component "0.2.3"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.clojure/java.classpath "0.2.2"]
                 [io.rkn/conformity "0.3.4" :exclusions [com.datomic/datomic-free]]
                 [untangled-spec "0.3.1" :scope "test" :exclusions [io.aviso/pretty]]
                 [com.taoensso/timbre "4.2.1"]
                 [democracyworks/datomic-toolbox "2.0.0" :exclusions [com.datomic/datomic-pro]]]


  :repositories [["releases" "https://artifacts.buehner-fry.com/artifactory/internal-release"]
                 ["third-party" "https://artifacts.buehner-fry.com/artifactory/internal-3rdparty"]
                 ["snapshots" "https://artifacts.buehner-fry.com/artifactory/internal-snapshots"]]

  :deploy-repositories [["releases" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-release"
                                     :snapshots     false
                                     :sign-releases false}]
                        ["snapshots" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-snapshots"
                                      :sign-releases false}]]

  :test-refresh {:report       untangled-spec.reporters.terminal/untangled-report
                 :changes-only true}

  :source-paths ["src"]
  :test-paths ["specs"]
  :resource-paths ["src" "resources"]

  )
