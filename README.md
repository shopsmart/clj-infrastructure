# clj-infrastructure

Database utilities for Brad's Deals / Shopsmart projects.  In the future this will include additional

[![Clojars Project](https://img.shields.io/clojars/v/bradsdeals/clj-infrastructure.svg)](https://clojars.org/bradsdeals/clj-infrastructure)


* Relational databases
  * Use clj-foundation templates to build SQL
  * Configuration assistance
  * Interact with a PreparedStatement as a regular Clojure function; template variables not substituted before (prepare-statement) become bind variable parameters to the Clojure function wrapping the PreparedStatement.
  * defstatement / defquery


Deployment/signing keys are under "clojars" in the usual place.  See https://gist.github.com/chrisroos/1205934 under "method 2" to import into your account.
