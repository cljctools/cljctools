# clj-go-libp2p
using go-libp2p from clojure


## archived - bad idea

- https://github.com/protonail/bolt-jna is an example
- building c-shared with go requires adding //export Foo to every function
- bold-jna does it namually, writing go code that re-exports functions for using https://github.com/protonail/bolt-jna/blob/master/vendor/src/protonail.com/bolt-jna/bolt_db.go
- even if we generate such code for libraries, it's a big effort and a white flag: basically saying, we cannot write what go does
- it's a reuse yes, but level of complexity grows because calling native fns, eventually will have to fork, make changes - all instead of writing
- bittomline: if library is that important that we want to use it on jvm, it should exist for jvm or be written in clojure