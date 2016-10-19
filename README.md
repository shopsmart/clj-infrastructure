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

## Coordinates

```clojure
:user {:repositories [["jitpack" "https://jitpack.io"]]}
```

* GroupId: com.github.shopsmart
* ArtifactId: clj-foundation
* Version: [![Release](http://jitpack.io/v/com.github.shopsmart/clj-infrastructure.svg)](https://jitpack.io/#shopsmart/clj-infrastructure)


