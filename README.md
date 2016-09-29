# clj-infrastructure

A library of utilities enhancing infrastructure services.

* Relational databases
  * Configuration assistance for all JDBC levels as well as the db library itself.
  * Interact with a PreparedStatement as a regular Clojure function

* AWS
  * Get the current user's access keys
  * Utilities to interact with EC2 instances


## TODO

### Test with-transaction in clojure.java.jdbc

* Does this automatically set autoCommit=false?
* Does this automatically add {:transaction? false} to execute! statements?
* Does this work with prepared statements?  (both clojure.java.jdbc/prepare-statement and db/prepare)
* Does it work with db-do-commands?

### Explicit transaction boundary helpers for db.clj

* Based on outcome of above, determine if we need our own
