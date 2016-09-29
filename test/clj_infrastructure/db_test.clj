(ns clj-infrastructure.db-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s :refer [=> =>*]]
            [clj-foundation.unit-test-common :as common]
            [clj-foundation.patterns :as p :refer [def-singleton-fn f]]
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
;; Then you can set (def current-deb :redshift) below

(defconfig secrets "REDSHIFT_SECRETS" "local-postgres.edn")

(def h2 {:classname   "org.h2.Driver"
         :subprotocol "h2:mem"
         :subname     "demo;DB_CLOSE_DELAY=-1"
         :user        "sa"
         :password    ""})


(def settings {:redshift {:test-table "integration_or_unit_test.transaction_test_777"
                          :spec (secrets :contentshift)}

               :h2 {:test-table "transaction_test_777"
                    :spec h2}})


;; Only the transactional tests use this
(def current-db :h2)

(def test-table (-> settings current-db :test-table))
(dbconfig-override :test-table test-table)
(def config (partial dbconfig {DB-SPEC (-> settings current-db :spec)
                               ABORT?-FN (constantly true)}))

(defn q [sql] (template/subst<- sql :test-table test-table))


(defn create-test-table [conn]
  (execute! "drop table if exists ${test-table};" CONNECTION conn)
  (execute! "create table if not exists ${test-table} (id bigint, description varchar(1000));" CONNECTION conn))


;; Basic database library functionality tests

(defquery find-animal "select ${select-columns} from test_data where ${where-column}=${where-val};"
  ABORT?-FN (constantly true))

(deftest database-access

  (testing "dbconfig mechanism"
    (dbconfig-override DB-SPEC h2)

    (is (= "sa" (dbconfig {} DB-SPEC :user)))

    (is (= :nothingness-and-emptiness (dbconfig {DB-SPEC :nothingness-and-emptiness} DB-SPEC))))

  (testing "Create table"
    (jdbc/db-do-commands
     (dbconfig {} DB-SPEC)
     "drop table if exists test_data;")

    (jdbc/db-do-commands
     (dbconfig {} DB-SPEC)
     (jdbc/create-table-ddl
      :test_data
      [[:id "INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 100, INCREMENT BY 1) PRIMARY KEY"]
       [:name "VARCHAR(200)"]
       [:sound "VARCHAR(40)"]])))

  (testing "Insert rows"
    (jdbc/insert-multi!
     (dbconfig {} DB-SPEC)
     :test_data
     [{:name "Rabbit"
       :sound "Awwww! What's up, Doc?"}
      {:name "Pig"
       :sound "Oink"}
      {:name "Cow"
       :sound "Moo"}
      {:name "Dog"
       :sound "RRRRuuuuff"}]))


  (testing "simple prepare with configuration set using dbconfig-override."
    (jdbc/with-db-connection [conn (dbconfig {} DB-SPEC)]
      (dbconfig-override SQL-FN jdbc/query CONNECTION conn ABORT?-FN (constantly true))

      (let [make-sound (prepare "select sound from test_data where name='Pig';")
            result     (make-sound ::row-fn :sound)]

        (is (= "Oink" (first result)))
        (is (= 1 (count result))))))


  (testing "Prepare with both templates and bind variables; configuration set using parameters"
    (jdbc/with-db-connection [conn (dbconfig {} DB-SPEC)]

      (let [make-sound (prepare "select ${column} from test_data where name=${animal};"
                                SQL-FN jdbc/query CONNECTION conn ABORT?-FN (constantly true)
                                :column "sound" ::row-fn :sound)]

        (is (= "Moo"                    (first (make-sound :animal "Cow"))))
        (is (= "Awwww! What's up, Doc?" (first (make-sound :animal "Rabbit"))))
        (is (= "RRRRuuuuff"             (first (make-sound :animal "Dog")))))))


  (testing "defquery substitutes variables correctly"
    (jdbc/with-db-connection [conn (dbconfig {} DB-SPEC)]
      (is (= {:name "Cow" :sound "Moo"}
             (first (find-animal CONNECTION conn
                                 :select-columns "name,sound"
                                 :where-column "sound"
                                 :where-val "'Moo'")))))))



(deftest transaction-behavior

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
