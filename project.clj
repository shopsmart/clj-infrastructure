(defproject com.github.shopsmart/clj-infrastructure "0.1.9"
  :description "Infrastructure helpers for AWS, database, etc."
  :url "https://github.com/shopsmart/clj-infrastructure"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :plugins [[lein-test-bang-bang "0.2.0"] ; lein test!! - Run each test NS in a separate JRE
            [lein-ancient "0.6.10"]       ; lein ancient - Check for outdated dependencies
            [lein-auto "0.1.2"]           ; e.g.: lein auto kbit   or lein auto test
            [lein-kibit "0.1.2"]]         ; lein kibit - Linter that suggests more idiomatic forms

  :repositories [["jitpack" "https://jitpack.io"]]

  :profiles {:test {:dependencies [[com.h2database/h2 "1.4.192"]
                                   [postgresql/postgresql "9.3-1102.jdbc41"]]}
             :repl {:dependencies [[com.h2database/h2 "1.4.192"]
                                   [postgresql/postgresql "9.3-1102.jdbc41"]]}}

  :jvm-opts ["-Xmx10g" "-Xms512m" "-XX:+UseParallelGC"]

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [prismatic/schema "1.1.1"]
                 [org.clojure/java.jdbc "0.6.2-alpha2"]
                 [com.github.shopsmart/clj-foundation "0.9.18"]])
