#!/bin/bash

repl(){
    clojure -A:core:repl
}

test-nodejs(){
    npm i
    clojure -A:core:cljs-test-runner -x node
}

test-jvm(){
    clojure -A:core:clj-test-runner
}

"$@"