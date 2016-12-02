(def task-options
  {:project  'bradsdeals/clj-infrastructure
   :version  "0.1.17"
   :project-name "clj-infrastructure"
   :project-openness :open-source

   :description "Infrastructure helpers for AWS, database, etc."
   :scm-url "https://github.com/shopsmart/clj-infrastructure"

   :test-sources "test"
   :test-resources nil})


(set-env! :resource-paths #{"resources"}
          :source-paths   #{"src"}

          :dependencies   '[[org.clojure/clojure   "1.8.0"]
                            [clojure-future-spec   "1.9.0-alpha14"]

                            [org.clojure/tools.logging "0.3.1"]
                            [prismatic/schema      "1.1.1"]
                            [org.clojure/java.jdbc "0.6.2-alpha2"]
                            [bradsdeals/clj-foundation "LATEST"]

                            ; Testing dependencies
                            [com.h2database/h2      "1.4.192" :scope "test"]
                            [postgresql/postgresql  "9.3-1102.jdbc41" :scope "test"]

                            ; Boot tasks
                            [bradsdeals/clj-boot       "LATEST"]])

(require '[clj-boot.core :refer :all])

(set-task-options! task-options)
