#!/bin/bash

repl(){
    clj -A:core:clj:test:repl
}

test-nodejs(){
    npm i
    # -i :integration
    # -n cljctools.socket.nodejs-net-test
    clojure -A:core:cljs:test:nodejs-net:cljs-test-runner -x node "$@"
}

test-jvm(){

    # -i :integration -i :example
    # -v cljctools.socket.java-net-test/core-async-blocking-put-in-catch
    clojure -A:core:clj:test:clj-test-runner "$@"
}

clean(){
    rm -rf .cpcache node_modules cljs-test-runner-out package-lock.json
}

"$@"