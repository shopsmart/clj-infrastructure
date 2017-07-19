(ns clj-infrastructure.db
  "Extensions to clojure.jdbc supporting straight string substitution into
  SQL query strings.  Substitutions are located by first searching the
  operating system environment, then the Java system environment,
  values specified at query execution time, and finally default values
  specified at query definition time.

  Variable values are specified by appending name/value pairs to either the
  query creation or execution functions in the following form:

  (my-query :var1 value1 :var2 \"value2\")

  Query execution functions may also specify a timeout value so that
  hung JDBC connections do not indefinitely hang the thread."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.test :refer [is]]
            [clojure.java.jdbc :as db]
            [schema.core :as s :refer [=> =>* defschema Any Str]]
            [clj-foundation.errors :as err :refer [failure?]]
            [clj-foundation.io :as io]
            [clj-foundation.config :refer [read-config]]
            [clj-foundation.templates :as template]
            [clj-foundation.millis :as millis]
            [clj-foundation.errors :as errors :refer [try* failure?]]
            [clj-foundation.conversions :as c :refer [convert]]
            [clj-foundation.data :as d :refer [any?]]
            [clj-foundation.patterns :as p :refer [f types something? nothing Nothing! KeywordValuePairs]])
  (:import [java.sql PreparedStatement SQLException])
  (:gen-class))

(def DB_EXEC_MODE_QUERY "query")
(def DB_EXEC_MODE_EXEC  "execute")

;; Error handling ----------------------------------------------------------------------------

(declare dbconfig)

(defrecord sql-failure [failures causes])

(defn failure-seqs
  "SQLException can have nested exceptions both via (.getCause e) and via (.getNextException e).
  Translate SQLException into a (sql-failure [failures causes]) record where failures and causes
  are recursive lazy seqs of (.getNextException e) and (.getCause e) respectively."
  [failure]
  (letfn [(sql-failures [f] (when f (lazy-seq (cons f (sql-failures (.getNextException f))))))
          (causes       [f] (when f (lazy-seq (cons f (causes (.getCause f))))))]
    (cond
      (instance? SQLException failure) (sql-failure. (sql-failures failure) (causes failure))
      (instance? Throwable failure)    (sql-failure. [] (causes failure)))))


;; Extend the failure? multimethod so that sql-failure instances are treated as failures.
(defmethod failure? sql-failure [_] true)


(defmacro try*-jdbc
  "Return results of executing body or a sql-failure containing any thrown exceptions
  unwrapped into seqs for easier debugging.  Note that failure-seqs is a failure according
  to the failure? multimethod from the errors namespace."
  [& body]
  (try
    (do ~@body)
    (catch Throwable t#
      (failure-seqs t#))))


(defmacro jdbc-succeeds
  "Asserts that the JDBC statements in body succeeded."
  [& body]
  `(is (sequential?
        (try [~@body]
             (catch Throwable t#
               (failure-seqs t#))))))


(s/defn fatal? :- s/Bool
  "Returns true if this exception's message matches any of the substrings in fatal-exceptions and
  false otherwise."
  [e :- Throwable]
  (let [ex-str                   (d/replace-nil (str e) "")
        fatal-exception-messages (dbconfig {} :fatal-exceptions)]
    (reduce (fn [fatal ex-str-substring] (if (string/includes? ex-str ex-str-substring) (reduced true))) false fatal-exception-messages)))


(s/defn any-fatal-exceptions? :- s/Bool
  "If any exceptions in the exceptions seq are fatal exceptions, returns true, else returns false.

  This function is suitable for use as an abort?-fn in retry-with-timeout."
  [exceptions :- [Throwable]]
  (any? fatal? exceptions))



;; Configuration -----------------------------------------------------------------------------


(def JDBC-TIMEOUT-MILLIS
  "Parameter constant for overriding the default query timeout in a SQL job."
  ::JDBC-TIMEOUT-MILLIS)


(def MAX-RETRIES
  "Parameter constant for overriding the maximum number of retries in a SQL job."
  ::MAX-RETRIES)


(def RETRY-PAUSE-MILLIS
  "Parameter constant for overriding the number of milliseconds to pause between retries in a SQL job."
  ::RETRY-PAUSE-MILLIS)


(def FATAL-EXCEPTIONS
  "Specify the list of fatal exception message substrings."
  ::FATAL-EXCEPTIONS)


(def ABORT?-FN
  "Specify a function to call when a query fails to determine if we should retry (if retries remain)"
  ::ABORT?-FN)


(def DB-SPEC
  "The clojure.java.jdbc db-spec map for opening a database connection."
  ::db-spec)


(def CONNECTION
  "A constant for specifying the database connection to use for the operation.  This is the result of
  applying get-connection or with-db-connection to a DB-SPEC."
  ::CONNECTION)


(def SQL-FN
  "A constant for specifying the function to use for executing SQL PreparedStatements.
  This function must have the following signature:

  (=> s/Any [{s/Keyword s/Any} (* s/Any) {s/Keyword s/Any}])

  The parameters are as follows:
  * A DB spec
  * Any number of bind variable arguments for the PreparedStatement
  * A Map containing clojure.java.jdbc options relevent for the actual clojure.java.jdbc function
    that will execute the SQL.

  Normally this function will return whatever clojure.java.jdbc returns, but other behaviors are possible
  (e.g.: transformations, local caching, etc.)"
  ::SQL-FN)


(def JOB-NAME
  "A constant for specifying a database job's name."
  ::JOB-NAME)


(s/defn db-setting? :- s/Any
  "A keyword represents a setting iff it has a namespace.  Else it's a template variable name.

  Returns the namespace name if present or nil if not."
  [kw :- s/Keyword]
  (namespace kw))


(defn- constant->keyword
  "Convert a namespaced database constant keyword to its corresponding (lower-case) configuration keyword."
  [k]
  (keyword (.toLowerCase (name k))))


(def ^:private dbconfig-settings
  "Define default db configuration map.  Clients can override values using dbconfig-override or with-dbconfig-overrides macro"
  (atom {:job-name            #(first (template/lines %))
         :abort?-fn           any-fatal-exceptions?
         :fatal-exceptions    ["Serializable isolation violation on table"
                               "current transaction is aborted, commands ignored until end of transaction block"
                               "only table or database owner can vacuum it"
                               "only table or database owner can analyze it"
                               "org.postgresql.util.PSQLException"]
         :max-retries         0
         :jdbc-timeout-millis (millis/<-minutes (* 60 24))
         :retry-pause-millis  (millis/<-seconds 5)
         ; Must be overridden later on:
         :sql-fn              nothing
         :db-spec             nothing
         :connection          nothing }))


(defn dbconfig
  "Return config values for the database library"
  [override-map & keys]
  (let [config-keys   (map constant->keyword keys)
        override-map' (into {} (map (f [k v] => [(constant->keyword k) v]) override-map))
        config-values (merge @dbconfig-settings override-map')]
    (apply read-config config-values config-keys)))


(defn- kvs->kv-pairs
  [[k v]]
  [(constant->keyword k) v])


(defn dbconfig-override
  "Override or set dbconfig values for this session.  dbconfig keys (above) must identify the config
  constant to change."
  [& kvs]
  (err/not-nil "kvs cannot be nil" kvs)
  (let [overrides (apply assoc {} (mapcat kvs->kv-pairs (partition 2 kvs)))]
    (swap! dbconfig-settings #(merge % overrides))))



(def VarMaps
  "A schema for a parsed kv argument list."
  {:template-vars {s/Keyword s/Any}
   :settings      {s/Keyword s/Any}
   :dblib-params  {s/Keyword s/Any}})


(def this-namespace (.toString *ns*))

(s/defn varmaps<- :- VarMaps
  "Partition kv pairs into :settings, :template-vars, and :dblib-params kvs maps.

  :: Keywords that are namespaced (defined in) clj-foundation.db are considered :settings.
  :: Keywords that are namepaced to any other namespace are considered :dblib-params.
  :  Keywords that have no namespace qualification are considered :template-vars."
  [kvs :- [s/Keyword s/Any]]
  (errors/must-be "Expecting even number of parameters" (even? (count kvs)))

  (->> kvs
       (partition 2)
       (into (vec @dbconfig-settings))
       (reduce
        (fn [result [k v]]
          (let [setting-namespace (db-setting? k)]
            (cond
              (nil? setting-namespace)             (update-in result [:template-vars] #(conj % [k v]))
              (= setting-namespace this-namespace) (update-in result [:settings]      #(conj % [(constant->keyword k) v]))
              :else                                (update-in result [:dblib-params]  #(conj % [(constant->keyword k) v])))))
        {:settings {} :template-vars {} :dblib-params {}})))


;; Connection and transaction management ---------------------------------------------------


(defn with-overrides
 "Save the current database library overrides.  When exiting scope, restore all of the prior
 dbconfig-settings"
  [f & args]
  (let [old-overrides @dbconfig-settings]
    (try
      (apply f args)
      (finally
        (reset! dbconfig-settings old-overrides)))))


(defmacro with-dbconfig-overrides
 "Save the current database library overrides.  When exiting scope, restore all of the prior
 dbconfig-settings."
 [& body]
 `(with-overrides (fn [] ~@body)))


(defmacro dbconfig-connection
  "Create a DYNAMIC scope binding to a new database connection identified by spec within the
  dbconfig-settings and executes body within that scope.  Saves the state of dbconfig-settings
  at the beginning and restores them at the end."
  [spec & body]
  `(with-dbconfig-overrides
    (clojure.java.jdbc/with-db-connection [new-conn# ~spec]
      (dbconfig-override ~DB-SPEC ~spec)
      (dbconfig-override ~CONNECTION new-conn#)
      ~@body)))


(defmacro dbconfig-transaction
  "Create a DYNAMIC scope binding to a new transaction identified by spec within the dbconfig-settings
  and executes body within that scope.  Saves the state of dbconfig-settings at the beginning and restores
  them at the end."
  [spec & body]
  `(with-dbconfig-overrides
    (clojure.java.jdbc/with-db-transaction [new-conn# ~spec]
      (dbconfig-override ~DB-SPEC ~spec)
      (dbconfig-override ~CONNECTION new-conn#)
      ~@body)))


;; SQL Logging -----------------------------------------------------------------------------


(s/defn censor-statement :- s/Str
  [statement :- s/Str]
  (let [replacements [[#"aws_access_key_id=[^;]+" "aws_access_key_id=XXX"]
                      [#"aws_secret_access_key=[^';]+" "aws_secret_access_key=XXX"]
                      [#"token=[^']+", "token=XXX"]]]

    (reduce (fn [cur-statement [match replacement]]
              (string/replace cur-statement match replacement))
            statement
            replacements)))


(s/defn log-censored-sql :- s/Any
  "Log a SQL command (in the case where it will be executed next).  Censors AWS
  credentials as used in the Redshift COPY command."
  [statement :- s/Str]

  (let [censored-statement (censor-statement statement)]
    (log/debug "Executing: <<EOF\n" censored-statement "\nEOF\n")))



(defn resolve-sql
  "sql-or-resource can be either literal SQL or a path to a SQL resource file.  Resolves this parameter
  to a SQL template string.

  & default-substitutions are substitution keyword/value pairs that will be used

  a) as SQL or JDBC override parameters if the variables are named using the configuration constants
  defined above.

  b) as default values for variables defined inside SQL.  Variable values defined here may be overridden by
  Java system variables or operating system environment variables in that order.

  Any undefined variables will remain as literals in the SQL string.  Use templates/interpolation-vars
  to determine if any variables remain undefined."
  [sql-or-resource & default-substitutions]

  (let [source-sql    (if (.endsWith (.toUpperCase sql-or-resource) ".SQL")
                        (io/read-file sql-or-resource)
                        sql-or-resource)]
    (apply template/partial-subst<- source-sql default-substitutions)))


(def DbSpec
  "A clojure.java.jdbc database spec map is a :keyword / value map."
  {s/Keyword s/Any})



(s/defn prepare :- s/Any
  "Create a SQL PreparedStatement using the specified parameters.  Parameters may be template
  variables to be substituted into the SQL string before preparing the statement, clj-foundation.db
  settings variables such as timeout values or the connection to use, or JDBC parameters to be passed
  to the prepare-statement function.  These are distinguished as follows:

  :: Keywords that are defined in clj-foundation.db are considered :settings.  These are referenced
     via the vars that are defined inside db.clj.
  :: Keywords that are namepaced to any other namespace are considered :dblib-params.  Legal values are
     any keyword/value pairs supported by clojure.java.jdbc/prepare-statement.
  :  Keywords that have no namespace qualification are considered :template-vars.  All template variables
     not defined in a where clause must be specified in prepare.  Any template variables that cannot
     be substituted into the SQL string will be converted into SQL bind variables in the PreparedStatement.

  Required :settings parameters for prepare:

  * CONNECTION

  Recommended to specify in prepare but may also be specified when calling the function returned by prepare
  (Must be specified either here or there--or SQL cannot be executed):

  * SQL-FN

  Recommended but not required:

  * JOB-NAME   (defaults to the first line of SQL if not specified)
  * ABORT?-FN  (defaults to clj-foundation.db/any-fatal-exceptions?)

  Refer to the docstrings for the above defs for more information on each setting.

  Returns a function that can accept additional kv parameters and execute the PreparedStatement.

  That function returns the results of calling:
  (sql-fn connection [prepared-statement bind-arg1 bind-arg2 ... bind-argn] {dblib-params})"

  [sql-template :- s/Str
   & kvs        :- [s/Keyword s/Any]]

  (errors/must-be "Expecting an even number of kv parameters" (even? (count kvs)))

  (let [{:keys
         [template-vars
          settings
          dblib-params]}     (varmaps<- kvs)

        config               (partial dbconfig settings)

        connection           (config CONNECTION)
        _                    (err/must-be "CONNECTION must be defined." (something? connection))

        [sql
         sql-argument-names] (template/sql-vars (apply resolve-sql sql-template (template/kv-vector<- template-vars)))

        jdbc-timeout         (/ (d/replace-nil (config JDBC-TIMEOUT-MILLIS) 0) 1000)  ; The API uses millis, but JDBC wants seconds

        prepared-statement   (db/prepare-statement
                              (:connection connection)
                              sql
                              (merge {:timeout jdbc-timeout} dblib-params))]

    (let [prepare-settings     settings
          prepare-dblib-params dblib-params]

      (fn [& kvs]
        (errors/must-be "Expecting an even number of kv parameters" (even? (count kvs)))

        (let [{:keys
               [template-vars
                settings
                dblib-params]} (varmaps<- (vec kvs))

              exec-params      (merge prepare-dblib-params dblib-params)
              exec-settings    (merge prepare-settings settings)
              config           (partial dbconfig exec-settings) ; (for DRYness)

              job-name         (let [jname (config :job-name)]
                                 (if (fn? jname) (jname sql) jname))
              sql-fn           (config :sql-fn)
              bind-values      (vec (map #(template/resolve-var template-vars %) sql-argument-names))
              sql+parameters   (apply conj [prepared-statement] bind-values)
              dblib-vector     (if (empty? exec-params)
                                 []
                                 [exec-params])

              retry-settings   (errors/->RetrySettings
                                (config :max-retries)
                                (+ (config :jdbc-timeout-millis) (millis/<-seconds 5)) ; Give JDBC some time before we timeout
                                (config :retry-pause-millis)
                                (config :abort?-fn))]

          (err/must-be "SQL-FN must be defined to execute a PreparedStatement." (something? sql-fn))

          (log-censored-sql sql)
          (apply err/retry-with-timeout
                 job-name
                 retry-settings
                 sql-fn connection sql+parameters dblib-vector))))))


(defmacro defquery
  "Define a function to query CONNECTION using the SQL resulting from
  loading the specified resource file and substituting the subsequent
  key-value pairs for the variables defined in the resource file.

  The resulting function can accept additional key-value pairs which
  may be used to either complete the query or to override the
  default values.

  The resulting function may be called multiple times, specifying
  additional or different variable substitutions each time if necessary."
  [function-name sql-or-resource & constant-kvs]
  (let [job-name (name function-name)]
    `(def ~function-name
       (fn [& runtime-kvs#]
         (let [kvs# (apply conj [~@constant-kvs] runtime-kvs#)
               q# (apply prepare ~sql-or-resource JOB-NAME ~job-name SQL-FN clojure.java.jdbc/query kvs#)]
           (q#))))))


(defmacro defstatement
  "Define a function to query CONNECTION using the SQL resulting from
  loading the specified resource file and substituting the subsequent
  key-value pairs for the variables defined in the resource file.

  The resulting function can accept additional key-value pairs which
  may be used to either complete the query or to override the
  default values.

  The resulting function may be called multiple times, specifying
  additional or different variable substitutions each time if necessary."
  [function-name sql-or-resource & constant-kvs]
  (let [job-name (name function-name)]
    `(def ~function-name
       (fn [& runtime-kvs#]
         (let [kvs# (apply conj [~@constant-kvs] runtime-kvs#)
               q# (apply prepare ~sql-or-resource JOB-NAME ~job-name SQL-FN clojure.java.jdbc/execute! kvs#)]
           (q#))))))


(defn execute!
  "Execute on CONNECTION the specified SQL or the SQL in the specified
  resource file, substituting the subsequent key-value pairs for the
  variables defined in the resource file."
  [sql-or-resource & variable-key-vals]
  (let [sql-fn (apply prepare sql-or-resource SQL-FN clojure.java.jdbc/execute! variable-key-vals)]
    (sql-fn)))


(defn query
  "Query CONNECTION using the specified SQL or the SQL in the specified
  resource file, substituting the subsequent key-value pairs for the
  variables defined in the resource file."
  [sql-or-resource & variable-key-vals]
  (let [sql-fn (apply prepare sql-or-resource SQL-FN clojure.java.jdbc/query variable-key-vals)]
    (sql-fn)))


(defschema QuerySubstitutions [s/Keyword s/Any])       ;; Really [(* Keyword Any)], but Schema doesn't know how to say that


(s/defn forall-substitutions :- {}      ; Return any map
  "query or execute! sql over a list of substitutions.

  sql-or-fn is:
    * A String pointing to a resource file with the SQL
    * A SQL string itself
    * A function (=> s/Str [{}])

  Takes an initial-result-map that will accumulate the results of executing
  SQL over all of the substitutions.

  When calling query or execute!, if the operation succeeds, call :on-success with
  the current result map, the query results, and the substitution list used as
  parameters to the sql operation.

  If the sql operation fails, call :on-failure with the current result map, the
  exception object, and the substitution list used as parameters to the sql
  operation.

  The success-function or error-function is expected to return a new map containing
  the new result of running the sequence of queries.

  If the error-function needs to abort, it can re-throw the exception.

  ;; Call like this:
  (apply for-all-substitutions queryfn result-map
    (letfn-map [(on-success [r q p] ...) (on-failure [r q p] ...)]) substitutions)

  ;; Or like this:
  (for-all-substitutions
    queryfn
    result-map
    (letfn-map
      [(on-success [r q p] ...)
      (on-failure [r q p] ...)])
    [:tab-name \"foo\"] [:tab-name \"bar\"] [:tab-name \"baz\"])"

  [execute-or-query-fn :-  (=> Any QuerySubstitutions)
   initial-result-map :-   {}
   callback-map :-         {:on-success (=> {} [(s/one {}                 "Current result")
                                                (s/one Any                "Query result")
                                                (s/one QuerySubstitutions "Query parameters")])
                            :on-failure (=> {} [(s/one {}                 "Current result")
                                                (s/one Throwable          "The exception")
                                                (s/one QuerySubstitutions "Query parameters")])}
   & substitution-lists :- [(s/optional QuerySubstitutions   "Query parameters")]]

  (reduce

   (fn [result-map substitution-list]
     (let [substitution-map (apply assoc {} substitution-list)
           success-fn (:on-success callback-map)
           error-fn (:on-failure callback-map)]
       (try
         (let [sql-result (apply execute-or-query-fn substitution-list)]
           (success-fn result-map sql-result substitution-map))
         (catch Exception e
           (error-fn result-map e substitution-map)))))

   initial-result-map
   substitution-lists))


;;-----------------------------------------------------------------------


(defn- row->kv-pair [result-row key-columns]
  [(str "'" (string/join "' && '" (mapcat (fn [col] [(str (name col) "'->'" (col result-row))]) key-columns)) "'") result-row])


(defn- add-row-to-result-map [key-columns result row]
  (let [[k v] (row->kv-pair row key-columns)
        prior-row (result k)]
    (if (contains? result k)
      (if (sequential? prior-row)
        (assoc result k (conj prior-row v))
        (assoc result k [prior-row v]))
      (assoc result k v))))

(defn- query-and-extract-key
  [query key-columns]
  (reduce (partial add-row-to-result-map key-columns) {} (query)))

(defn- extract-keys [arg-list default-keys]
  (let [possible-keys (first arg-list)
        have-keys (vector? possible-keys)
        keys (if have-keys possible-keys default-keys)
        variable-key-vals (if have-keys (rest arg-list) arg-list)]
    [keys variable-key-vals]))


(defn keyed-query-fn
  "Create a function to run the specified query or sql resource file on CONNECTION,
  extract the (concatinated) key values from each result row, and transform
  the result into a map keyed by each row's (concatinated) key, with the
  row itself being the value.

  The initial element of the variadic parameter may optionally be a
  vector of keywords specifying the (concatinated) key of the table.
  The rest must be key-value pairs specifying default substitution
  variable values.

  If the key vector is not specified when creating the query function,
  it must be specified when calling the query function.

  If the key vector does not uniquely identify a row in the result set,
  keys identifying multiple rows will be associated with a vector
  containing the rows referenced by the key."
  [sql-or-resource & keys-or-variable-key-vals]
  (let [[key-columns variable-key-vals] (extract-keys keys-or-variable-key-vals nil)
        query (apply prepare sql-or-resource SQL-FN query variable-key-vals)]

    (fn [& keys-or-extra-key-vals]
      (let [[key-columns extra-key-vals] (extract-keys keys-or-extra-key-vals key-columns)]
        (if (empty? key-columns)
          (throw (IllegalArgumentException. "A keyed query must specify the column(s) that make up the key.")))
        (query-and-extract-key #(apply query extra-key-vals) key-columns)))))


(defn keyed-query
  "Run a query returnining keyed results.

  The initial element of the variadic parameter must be a
  vector of keywords specifying the (concatinated) key of the table.

  The remaining parameters must be key-value pairs specifying substitution
  variable values for any variables defined in the SQL code or the SQL
  resource file."
  [sql-or-resource & keys-or-variable-key-vals]
  (let [keyed-query (apply keyed-query-fn sql-or-resource keys-or-variable-key-vals)]
    (keyed-query)))


(defmacro defkeyedquery
  "Define the specified query function, extract the (concatinated) key values from
  each result row, and transform the result into a map keyed by each row's
  (concatinated) key, with the row itself being the value.

  The key is specified as a vector of keywords describing the (concatinated) key
  of the query results.  This key must be the initial argument after the query itself.

  If the key vector does not uniquely identify a row in the result set,
  keys identifying multiple rows will be associated with a vector
  containing the rows referenced by the key."
  [function-name sql-or-resource & default-subs]
  `(def ~function-name (keyed-query-fn ~sql-or-resource ~@default-subs)))


(defn keystring->where-conditions
  "Transform a keyed query result's key string into a string containing where clause
  conditions usable in a sql query to retrieve any row again that utilizes the same
  key columns/values."
  [key-string]
  (let [key-string-parts (string/split key-string #"^'|' && '|'->'|'$")
        col-value-pairs (partition 2 (rest key-string-parts))
        where-conditions (map (fn [[col value]] (str col "='" value "'")) col-value-pairs)]
    (string/join " and " where-conditions)))


(defn sql->stmt-detail-map [stmt-text & {:keys [exec-mode commit? binds opt-map op-comment] :as detail-kw}]
  (let [detail-map
    (merge
        {:stmt-text stmt-text
         :op-comment op-comment
         :exec-mode (or exec-mode DB_EXEC_MODE_QUERY)}
        (when binds {:binds binds})
        (when commit? {:commit? commit?})
        (when opt-map {:opt-map opt-map}))]
     detail-map))


(defn run-sql-stmts-in-transaction
    [conn-or-spec stmt-detail-vec]

    """
    Runs the provided set of queries with the specified options.

    sql-vec A vector of maps containing statement details.  A statement detail map
            contains details such as sql text, execution mode, options, etc.

        e.g.

        [
          { :stmt-text  \"set random_page_cost = 1\"
            :exec-mode  DB_EXEC_MODE_EXEC
            :op-comment \"Encourage index usage\"
            :binds      nil
            :opt-map    {:as-arrays? true}
            :commit?    false}
          { :stmt-text  \"select 1\"
            :exec-mode  DB_EXEC_MODE_QUERY
            :op-comment \"Extract new or modified user details\" }  ]
    """

    (dbconfig-connection conn-or-spec
      (clojure.java.jdbc/with-db-transaction [conn (dbconfig {} "connection")]

        (doall
          (for [stmt-detail-map stmt-detail-vec]
            (run-statement conn stmt-detail-map))))))


(defn run-statement
  [conn {:keys [stmt-text exec-mode op-comment binds opt-map commit?] :as stmt-detail-map}]

  """
  Runs the statement represented by the supplied statement detail
  map.

  Returns: The statement detail map updated with {:result result-data}
  """

  (log/info (str "Running statement: [" stmt-text "] ..."))
  (let [sql-params (concat [stmt-text] binds)]
    (when op-comment
      (log/info (str "Operation comment: " op-comment)))
    (let [updated-map
      (assoc-in stmt-detail-map [:result]
        (cond
          (= exec-mode DB_EXEC_MODE_QUERY)
            (do
              (log/debug "Issuing statement as query (results expected) ...")
              (clojure.java.jdbc/query conn sql-params opt-map))
          (= exec-mode DB_EXEC_MODE_EXEC)
            (do 
              (log/debug "Issuing statement as execution (no results expected) ...")
              (clojure.java.jdbc/execute! conn sql-params opt-map))))]
      (when commit?
        (.commit (:connection conn)))
      updated-map)))
