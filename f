#!/bin/bash

repl(){
    clojure -A:repl
}

test-nodejs(){
    npm i
    clojure -A:cljs-test-runner -x node
}

test-jvm(){
    clojure -A:clj-test-runner
}

"$@"