#!/bin/bash

repl(){
    clojure -A:repl
}

test-node(){
    npm i
    clojure -A:cljs-test-runner -x node
}

"$@"