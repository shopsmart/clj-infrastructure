# clj-infrastructure

* Relational databases
  * Use clj-foundation templates to build SQL
  * Configuration assistance
  * Interact with a PreparedStatement as a regular Clojure function; template variables not substituted before (prepare-statement) become bind variable parameters to the Clojure function wrapping the PreparedStatement.
  * defstatement / defquery

* AWS (future)
  * Get the current user's access keys
  * Utilities to interact with EC2 instances
  * s3 up/download

* Dropbox
 * API v1 wrappers

## Config setup

Export environment variable(s) (or set on command line) for EDN format configuration file location.

Set environment variable to override default configuration file location:

    $ export LIB_CLJ_INFRA_CONFIG_FILE_PATH         "$HOME/conf/bd/common/clj/secret/clj-infrastructure.config.secret.edn"

## Coordinates

```clojure
:user {:repositories [["jitpack" "https://jitpack.io"]]}
```

* GroupId: com.github.shopsmart
* ArtifactId: clj-foundation
* Version: [![Release](http://jitpack.io/v/com.github.shopsmart/clj-infrastructure.svg)](https://jitpack.io/#shopsmart/clj-infrastructure)


