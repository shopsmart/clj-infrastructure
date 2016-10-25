(ns clj-infrastructure.db-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s :refer [=> =>*]]
            [clj-foundation.unit-test-common :as common]
            [clj-foundation.patterns :as p :refer [def-singleton-fn f letfn-map]]
            [clj-foundation.errors :as err :refer [try*]]
            [clj-foundation.millis :as millis]
            [clj-foundation.templates :as template]
            [clj-foundation.config :refer [defconfig]]
            [clj-infrastructure.db :refer :all]
            [clojure.java.jdbc :as jdbc])
  (:import [java.sql SQLException]))


(common/register-fixtures)


;; To test against redshift, set the environment variable REDSHIFT_SECRETS to
;; the path of an EDN file in the form:
;; {
;; :contentshift {:subprotocol "postgresql"
;;                :subname "<redshift-hostname>"
;;                :user "contentshift"
;;                :password "<password>"
;;                :driver "org.postgresql.Driver"
;;                :tcpKeepAlive true
;;                :timeout-seconds 900}
;; }
;;
;; Then you can set (def current-db :redshift) below

(defconfig secrets "REDSHIFT_SECRETS" "local-postgres.edn")

(def h2 {:classname   "org.h2.Driver"
         :subprotocol "h2:mem"
         :subname     "demo;DB_CLOSE_DELAY=-1"
         :user        "sa"
         :password    ""})



(def current-db :h2)


(defn test-table [basename] (str basename (System/getProperty "user.name"))) ; so it's impossible for two users' tests to conflict

(def settings {:redshift {:test-table     (test-table "transaction_test_777")
                          :test-table-2   (test-table "transaction_test_999")
                          :spec           (secrets :contentshift)}

               :h2 {:test-table   "transaction_test_777"
                    :test-table-2 "transaction_test_999"
                    :spec         h2}})


(def test-table (-> settings current-db :test-table))

(def config (partial dbconfig {DB-SPEC       (-> settings current-db :spec)
                         ABORT?-FN     (constantly true)
                         :test-table   (-> settings current-db :test-table)
                         :test-table-2 (-> settings current-db :test-table-2)}))

(dbconfig-override :test-table (config :test-table))

(defn q [sql] (template/subst<- sql :test-table test-table))


(defstatement j-insert "insert into ${table} values ${values}")
(defstatement j-update "update ${table} set ${values} where ${where}")
(defquery     j-select "select * from ${table} ${where}" :where "")


(defn create-test-table
  ([conn table-name]
   (execute! "drop table if exists ${test-table-name};"
             CONNECTION conn
             :test-table-name table-name)
   (execute! "create table if not exists ${test-table-name} (id bigint, description varchar(1000));"
             CONNECTION conn
             :test-table-name table-name))
  ([conn]
   (create-test-table conn (config :test-table))))


;; Basic database library functionality tests

(defquery find-animal "select ${select-columns} from test_data where ${where-column}=${where-val};"
  ABORT?-FN (constantly true))




(deftest infinite-loop-retry-fixed
  (jdbc/with-db-transaction [trans1 (config DB-SPEC)]
    (create-test-table trans1 (config :test-table))
    (is (thrown? SQLException (j-update CONNECTION trans1 :table (config :test-table) :values "blah = blah" :where "invalid sql")))))

(deftest database-access

  (testing "Create table"
    (jdbc/db-do-commands
     (config DB-SPEC)
     "drop table if exists test_data;")

    (jdbc/db-do-commands
     (config DB-SPEC)
     (jdbc/create-table-ddl
      :test_data
      [[:id "INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 100, INCREMENT BY 1) PRIMARY KEY"]
       [:name "VARCHAR(200)"]
       [:sound "VARCHAR(40)"]])))

  (testing "Insert rows"
    (jdbc/insert-multi!
     (config DB-SPEC)
     :test_data
     [{:name "Rabbit"
       :sound "Awwww! What's up, Doc?"}
      {:name "Pig"
       :sound "Oink"}
      {:name "Cow"
       :sound "Moo"}
      {:name "Dog"
       :sound "RRRRuuuuff"}]))


  (testing "dbconfig mechanism"
    (dbconfig-override DB-SPEC h2)

    (is (= "sa" (config DB-SPEC :user)))

    (is (= :nothingness-and-emptiness (dbconfig {DB-SPEC :nothingness-and-emptiness} DB-SPEC))))


  (testing "dbconfig-connection and dbconfig-transaction"
    (dbconfig-connection (config DB-SPEC)
      (dbconfig-override SQL-FN jdbc/query
                         ABORT?-FN (constantly true))

      (is (= "Oink" (first (query "select sound from test_data where name='Pig';" ::row-fn :sound))))

      (try
        (dbconfig-transaction (config DB-SPEC)
                              (j-update :table "test_data" :values "name='Wilbur'" :where "sound='Oink'")
                              (throw (RuntimeException. "Abort this transaction!")))
        (catch RuntimeException e))

      (is (= "Oink" (first (query "select sound from test_data where name='Pig';" ::row-fn :sound)))))


    (dbconfig-connection (config DB-SPEC)
      (dbconfig-override SQL-FN jdbc/query
                         ABORT?-FN (constantly true)
                         :sound-column "sound"
                         :default-animal "Pig")     ; Note: have to be careful what you override: the names are global!

      (testing "Prepare/execute"
        (let [make-sound (prepare "select ${sound-column} from test_data where name='${default-animal}';")
              result     (make-sound ::row-fn :sound)]

          (is (= "Oink" (first result)))
          (is (= 1 (count result)))))

      (testing "Prepare/execute from a SQL file"
        (let [something (prepare "select-something.sql" :something "9")
              result     (something ::row-fn :res)]

          (is (= 9 (first result)))
          (is (= 1 (count result)))))

      (testing "Prepare/execute, but overridding the dbconfig-override variable"
        (let [make-sound (prepare "select ${sound-column} from test_data where name='${default-animal}';" :default-animal "Cow")
              result     (make-sound ::row-fn :sound)]

          (is (= "Moo" (first result)))
          (is (= 1 (count result)))))))


  (testing "Prepare with both templates and bind variables; configuration set using parameters"
    (dbconfig-connection (config DB-SPEC)
      (dbconfig-override SQL-FN jdbc/query
                         ABORT?-FN (constantly true))

      (let [make-sound (prepare "select ${column} from test_data where name=${animal};"
                                SQL-FN jdbc/query ABORT?-FN (constantly true)
                                :column "sound" ::row-fn :sound)]

        (is (= "Moo"                    (first (make-sound :animal "Cow"))))
        (is (= "Awwww! What's up, Doc?" (first (make-sound :animal "Rabbit"))))
        (is (= "RRRRuuuuff"             (first (make-sound :animal "Dog")))))))


  (testing "defquery substitutes variables correctly"
    (dbconfig-connection (config DB-SPEC)
      (dbconfig-override SQL-FN jdbc/query
                         ABORT?-FN (constantly true))

      (is (= {:name "Cow" :sound "Moo"}
             (first (find-animal :select-columns "name,sound"
                                 :where-column "sound"
                                 :where-val "'Moo'")))))))


;; execute-forall testing ===============================================================================



(defn query'
  "Always happy!"
  [& substitutions]
  [{:first-name "John" :last-name "Doe"}
   {:first-name "Jane" :last-name "Doe"}])


(defn query''
  "Can't query nothing; else we're happy!"
  [& substitutions]
  (when (= (second substitutions) "nothing")
    (let [sql "select * from ${table};"]
      (throw (IllegalStateException. (apply template/subst<- sql substitutions)))))

  [{:first-name "John" :last-name "Doe"}
   {:first-name "Jane" :last-name "Doe"}])


(defn query'''
  "Always unhappy :-(  Stop. Right. Now!"
  [& substitutions]
  (throw (IllegalStateException. "halt")))


;; One function to rule all the query executors

(defn execute-forall [query]
  "Execute a forall-substitutions loop for a specified query executor function and dummy data."
  (forall-substitutions

   ;; A function that accepts substitution variables, queries/executes a statement, and returns results.
   query

   ;; Starting synopsis
   {:call-count 0
    :errors []
    :tables []}

   ;; Callbacks
   (letfn-map
    [(on-success [current-result query-result substitutions]
                 (-> current-result
                     (update-in [:call-count] inc)
                     (update-in [:tables] #(conj % (:table substitutions)))))

     (on-failure [current-result exception substitutions]
                 (let [next-result (-> current-result
                                       (update-in [:call-count] inc)
                                       (update-in [:errors] #(conj % exception)))]

                   (if (= "halt" (.getMessage exception))
                     (throw (ex-info "Halting!" next-result exception))
                     next-result)))])

   ;; substitution values
   [:table "nothing"]
   [:table "nowhere"]
   [:table "nobody"]))



(deftest forall-substitutions-test
  (testing "forall-substitutions calls the same query executor multiple times with different substitution variables each time"

    (testing "Successful queries call the success function and populate the results map."
      (let [job-results (execute-forall query')]
        (is (= 3 (:call-count job-results)))
        (is (empty? (:errors job-results)))
        (is (= ["nothing" "nowhere" "nobody"] (:tables job-results)))))

    (testing "A mixture of successes/failures call the correct functions and populate the results map."
      (let [job-results (execute-forall query'')]
        (is (= 3 (:call-count job-results)))
        (is (= 1 (count (:errors job-results))))
        (is (= "select * from nothing;" (.getMessage (first (:errors job-results)))))
        (is (= ["nowhere" "nobody"] (:tables job-results)))))

    (testing "Throwing an exception from error-fn halts processing.  This particular exception contains the job state."
      (let [job-results (try* (execute-forall query'''))]
        (is (ex-data job-results))

        (when-let [state (ex-data job-results)]
          (is (= 1 (:call-count state)))
          (is (= 1 (count (:errors state))))
          (is (instance? IllegalStateException (first (:errors state))))
          (is (empty? (:tables state))))))))


;; any-fatal-exceptions? ===============================================================================


(let [e1    (Exception. "Just one")

      e2'   (Exception. "I'm guilty.")
      e2    (Exception. "An exception with a cause." e2')

      e3''  (Exception. "only table or database owner can vacuum it")
      e3'   (Exception. "Look deeper" e3'')
      e3    (Exception. "An exception with a fatal cause." e3')

      e4''' (Exception. "Lotssss offff causses, my Preciousss!")
      e4''  (Exception. "only table or database owner can vacuum it" e4''')
      e4'   (Exception. "Look deeper" e4'')
      e4    (Exception. "An exception with a fatal cause." e4')

      e5    (ex-info "Master exception" {} e4)]

  (deftest fatal?-test
    (testing "(.substring (.getMessage e) ... matching a fatal-exception is fatal"
      (is (fatal? (Exception. "ERROR: only table or database owner can vacuum it")))
      (is (not (fatal? (Exception. "Some Random Exception."))))))


  (deftest any-fatal-exceptions?-test
    (testing "True if any exception in exceptions is fatal"
      (is (not (any-fatal-exceptions? (err/seq<- e1))))
      (is (not (any-fatal-exceptions? (err/seq<- e2))))
      (is (any-fatal-exceptions? (err/seq<- e3)))
      (is (any-fatal-exceptions? (err/seq<- e4)))
      (is (any-fatal-exceptions? (err/seq<- e5))))))



;; Transactional tests ==================================================================================


(deftest transaction-behavior

  (testing "Multiple transactions can write to the same table without killing each other"
    ;; Setup/initialization
    (jdbc/with-db-connection [conn (config DB-SPEC)]
      (create-test-table conn (config :test-table))
      (j-insert CONNECTION conn
                   :table (config :test-table)
                   :values "(10, 'Awwww! What''s up, Doc?'), (20, 'Moo'), (30, 'Oink')")

      (create-test-table conn (config :test-table-2))
      (j-insert CONNECTION conn
                   :table (config :test-table-2)
                   :values "(1, 'Chirp')"))

    (testing "Contending inserts from two transactions"
      (jdbc/with-db-transaction [trans1 (config DB-SPEC)]
        (testing "Insert into table1 from trans1"
          (j-insert CONNECTION trans1
                       :table (config :test-table)
                       :values "(40, 'Caaaaw')")

          (testing "Insert into table2 from a new transaction while trans1 is still open"
            (jdbc/with-db-transaction [trans2 (config DB-SPEC)]
              (j-insert CONNECTION trans2
                           :table (config :test-table-2)
                           :values "(2, 'Aaarf')")))

          (testing "Insert into table2 from initial (longer-running) transaction after trans2 commits"
            (j-insert CONNECTION trans1
                         :table (config :test-table-2)
                         :values "(3, 'Woof')")))))

    (testing "Resolve row-level updates/locks"
      (let [trans2-future (atom nil)]
        (jdbc/with-db-transaction [trans1 (config DB-SPEC)]
          (testing "Update table1 from trans1"
            (j-update CONNECTION trans1
                    :table (config :test-table)
                    :values "description='Bugs Bunny'"
                    :where "id=10")

            (testing "Update table1 from a new transaction while trans1 is still open"
              (reset! trans2-future
                      (future
                        (jdbc/with-db-transaction [trans2 (config DB-SPEC)]
                          (j-update CONNECTION trans1
                                  :table (config :test-table)
                                  :values "description='Bugs'"
                                  :where "id=10")))))

            (Thread/sleep 1000)

            (testing "Insert into table2 from initial (longer-running) transaction after trans2 commits"
              (j-insert CONNECTION trans1
                           :table (config :test-table-2)
                           :values "(4, 'Neeeigh')"))))))

    (testing "Ensure row-level updates/locks resolved"
      (jdbc/with-db-connection [conn (config DB-SPEC)]
        (is (= "Bugs" (:description
                       (first
                        (j-select CONNECTION conn
                                  :table (config :test-table)
                                  :where "where id=10"))))))))

  (testing "transactions with db.clj library respect exceptions"
    (jdbc/with-db-connection [conn (config DB-SPEC)]
      (create-test-table conn)

      (jdbc-succeeds
       (execute! "insert into ${test-table} values (1, 'Awwww! What''s up, Doc?'), (2, 'Moo');" CONNECTION conn)
       (execute! "insert into ${test-table} values (3, 'Oink')" CONNECTION conn)

       (try
         (jdbc/with-db-transaction [trans (config DB-SPEC)]
           (execute! "insert into ${test-table} values (10, 'Awwww! What''s up, Doc?'), (20, 'Moo');" CONNECTION trans)
           (execute! "insert into ${test-table} values (30, 'Oink')" CONNECTION trans)
           (throw (ex-info "Oh NO!")))
         (catch Exception e)))

      (testing "Inserts outside the transaction succeeded."
        (is (seq (query "select * from ${test-table} where id < 10" CONNECTION conn))))

      (testing "The failed transaction's inserts rolled back."
        (is (not (seq (query "select * from ${test-table} where id >= 10" CONNECTION conn)))))))


  (testing "transactions with db.clj library respect explicit rollback"
    (jdbc/with-db-connection [conn (config DB-SPEC)]
      (create-test-table conn)

      (jdbc-succeeds
       (execute! "insert into ${test-table} values (1, 'Awwww! What''s up, Doc?'), (2, 'Moo');" CONNECTION conn)
       (execute! "insert into ${test-table} values (3, 'Oink')" CONNECTION conn)

       (jdbc/with-db-transaction [trans (config DB-SPEC)]
         (execute! "insert into ${test-table} values (10, 'Awwww! What''s up, Doc?'), (20, 'Moo');" CONNECTION trans)
         (execute! "insert into ${test-table} values (30, 'Oink')" CONNECTION trans)
         (execute! "rollback" CONNECTION trans)))

      (testing "Inserts outside the transaction succeeded."
        (is (seq (query "select * from ${test-table} where id < 10" CONNECTION conn))))

      (testing "The failed transaction's inserts rolled back."
        (is (not (seq (query "select * from ${test-table} where id >= 10" CONNECTION conn)))))))


  (testing "transactions with db.clj library prepared statements respect explicit rollback"
    (letfn [(prepare-insert [conn] (prepare "insert into ${test-table} values (${id}, ${sound})"
                                            CONNECTION conn
                                            SQL-FN jdbc/execute!))]

      (jdbc/with-db-connection [conn (config DB-SPEC)]
        (create-test-table conn)

        (jdbc-succeeds
         (let [insert-outside (prepare-insert conn)]
           (insert-outside :id 1 :sound "Awwww! What''s up, Doc?")
           (insert-outside :id 2 :sound "Moo")
           (insert-outside :id 3 :sound "Oink"))

         (jdbc/with-db-transaction [trans (config DB-SPEC)]
           (let [insert-inside (prepare-insert trans)]
             (insert-inside :id 10 :sound "Awwww! What''s up, Doc?")
             (insert-inside :id 20 :sound "Moo")
             (insert-inside :id 30 :sound "Oink")
             (execute! "rollback" CONNECTION trans))))

       (testing "Inserts outside the transaction succeeded."
         (is (seq (query "select * from ${test-table} where id < 10" CONNECTION conn))))

       (testing "The failed transaction's inserts rolled back."
         (is (not (seq (query "select * from ${test-table} where id >= 10" CONNECTION conn))))))))


  (testing "Validate (.setAutoCommit conn false) doesn't respect autocommit=false in db-do-commands."
      (jdbc/with-db-connection [conn (config DB-SPEC)]

        (.setAutoCommit (:connection conn) false)

        (jdbc-succeeds
         (jdbc/db-do-commands
          conn
          [(q "drop table if exists ${test-table};")
           (q "create table if not exists ${test-table} (id bigint, description varchar(100));")]))

        (jdbc-succeeds
         (jdbc/db-do-commands
          conn
          [(q "insert into ${test-table} values (1, 'Awwww! What''s up, Doc?'), (2, 'Moo')")
           (q "insert into ${test-table} values (3, 'Oink')")]))

        ;; If creating a separate connection/transaction shows that the above data is visible, thus autocommit
        ;; is not being respected.
        (jdbc/with-db-connection [conn2 (config DB-SPEC)]
          (is (not (= (:connection conn) (:connection conn2)))) ; Prove clojure.jdbc isn't reusing connections
          (is (.getAutoCommit (:connection conn2)))             ; ...and that conn2 has its own autoCommit property
          (is (not (empty? (jdbc/query conn2 [(q "select * from ${test-table} where id=3")])))))))


  (testing ":transaction? true behavior"
      (jdbc/with-db-connection [conn (config DB-SPEC)]

        (.setAutoCommit (:connection conn) false)

        (jdbc-succeeds
         (create-test-table conn)

         (jdbc/execute!
          conn
          [(q "insert into ${test-table} values (1, 'Awwww! What''s up, Doc?'), (2, 'Moo');
              insert into ${test-table} values (3, 'Oink')")]
          {:transaction? true}))

        (testing "Show that the above data has been fully committed in spite of autoCommit=false"
          (jdbc/with-db-connection [conn2 (config DB-SPEC)]
            (is (not (= (:connection conn) (:connection conn2)))) ; Prove clojure.jdbc isn't reusing connections
            (is (.getAutoCommit (:connection conn2)))             ; ...and that conn2 has its own autoCommit property
            (is (not (empty? (jdbc/query conn2 [(q "select * from ${test-table} where id=3")]))))))))


  (testing ":transaction? false behavior"
      (jdbc/with-db-connection [conn (config DB-SPEC)]

        (.setAutoCommit (:connection conn) false)

        (jdbc-succeeds
         (create-test-table conn)

         (jdbc/execute!
          conn
          [(q "insert into ${test-table} values (1, 'Awwww! What''s up, Doc?'), (2, 'Moo');
               insert into ${test-table} values (3, 'Oink')")]
          {:transaction? false})

         (.rollback (:connection conn)))

        (testing "Show that autocommit was respected (the explicit rollback removes the data from the insert)"
          (jdbc/with-db-connection [conn2 (config DB-SPEC)]
            (is (empty? (jdbc/query conn2 [(q "select * from ${test-table} where id=3")])))))))


  (testing "Only fully committed data is visible; rollback does not affect prior commits."
      (jdbc/with-db-connection [conn (config DB-SPEC)]

        (.setAutoCommit (:connection conn) false)

        (jdbc-succeeds
         (create-test-table conn)

         (jdbc/execute!
          conn
          [(q "insert into ${test-table} values (1, 'Awwww! What''s up, Doc?'), (2, 'Moo');
            insert into ${test-table} values (3, 'Oink')")]
          {:transaction? false})

         (.commit (:connection conn))

         (jdbc/execute!
          conn
          [(q "insert into ${test-table} values (4, 'moo'), (5, 'Keeeer');
            insert into ${test-table} values (6, 'woof')")]
          {:transaction? false})

         (.rollback (:connection conn)))

        (testing "Show that commit was respected"
          (jdbc/with-db-connection [conn2 (config DB-SPEC)]
            (is (not (empty? (jdbc/query conn2 [(q "select * from ${test-table} where id<=3")]))))))

        (testing "Show that rollback was respected also"
          (jdbc/with-db-connection [conn2 (config DB-SPEC)]
            (is (empty? (jdbc/query conn2 [(q "select * from ${test-table} where id>3")])))))))


  (testing "autoCommit behavior"
      (jdbc/with-db-connection [conn (config DB-SPEC)]
        (try

          (.setAutoCommit (:connection conn) true) ; It's the default, per docs, but just to be sure...

          (jdbc-succeeds
           (create-test-table conn)

           (testing "Explicit SQL transactions supercede :transaction? true"
             (jdbc/execute!
              conn
              [(q "begin;
                insert into ${test-table} values (1, 'Awwww! What''s up, Doc?');
                rollback;")]
              {:transaction? true})

             (jdbc/with-db-connection [conn2 (config DB-SPEC)]
               (is (empty? (jdbc/query conn2 [(q "select * from ${test-table} where id<=3")])))))


           (testing "Autocommit"
             (create-test-table conn)

             (jdbc/execute! conn ["begin;"] {:transaction? true})
             (jdbc/execute!
              conn [(q "insert into ${test-table} values (3, 'Oink');")] {:transaction? true})
             (jdbc/execute!
              conn [(q "insert into ${test-table} values (4, 'woof');")] {:transaction? true})
             (jdbc/execute! conn ["rollback;"] {:transaction? true})

             (jdbc/with-db-connection [conn2 (config DB-SPEC)]
               (is (not (empty? (jdbc/query conn2 [(q "select * from ${test-table} where id<=3")]))))))


           (testing "Rollback with autocommit=true"
             (create-test-table conn)

             (jdbc/execute!
              conn
              [(q "insert into ${test-table} values (5, 'moo');")]
              {:transaction? false})

             (testing "Show that commit was respected (d'oh; autocommit is on)"
               (jdbc/with-db-connection [conn2 (config DB-SPEC)]
                 (is (not (empty? (jdbc/query conn2 [(q "select * from ${test-table}")]))))))

             (testing "Show that rollback is NOT respected (because autocommit is on)"

               ;; Only Redshift/Postgres throws this exception
               (if (= current-db :redshift)
                 (try
                   (.rollback (:connection conn))
                   (is (not "Since autoCommit is enabled, we should never get here."))
                   (catch SQLException e
                     (is (= "Cannot rollback when autoCommit is enabled." (.getMessage e))))))

               (jdbc/with-db-connection [conn2 (config DB-SPEC)]
                 (is (not (empty? (jdbc/query conn2 [(q "select * from ${test-table}")]))))))))))))


(run-tests)
