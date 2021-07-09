#!/bin/bash

repl(){
    clojure -A:core:clj:test:repl
}

test-nodejs(){
    npm i
    clojure -A:core:cljs:test:cljs-test-runner -x node
}

test-jvm(){
    clojure -A:core:clj:test:clj-test-runner
}

"$@"