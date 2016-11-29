(def project  'bradsdeals/clj-infrastructure)
(def version  "0.1.17")
(def project-name "clj-infrastructure")
(def project-openness :open-source)

(def description "Infrastructure helpers for AWS, database, etc.")
(def scm-url "https://github.com/shopsmart/clj-infrastructure")


(set-env! :resource-paths #{"resources"}
          :source-paths   #{"src" "test"}
          :repositories #(conj % ["clojars-push" {:url "https://clojars.org/repo/"
                                                  :username (System/getenv "CLOJARS_USER")
                                                  :password (System/getenv "CLOJARS_PASS")}])

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

(set-task-options! project project-name project-openness description version scm-url)
