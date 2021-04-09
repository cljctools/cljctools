#!/bin/bash

repl(){
    clj -A:core:clj:test:repl
}

test-nodejs(){
    # npm i
    # -i :integration
    # -n cljctools.socket.nodejs-net-test
    # -v cljctools.socket.java-net-test/core-async-blocking-put-in-catch
    clojure \
        -A:core:edit-test:cljs-test-runner \
        -m cljs-test-runner.main \
        -d src/edit-test \
        -x node
}

test-jvm(){
    clojure \
        -A:core:edit-test:clj-test-runner \
        -m cognitect.test-runner \
        -d src/edit-test
}

clean(){
    rm -rf .cpcache node_modules cljs-test-runner-out package-lock.json
}

"$@"