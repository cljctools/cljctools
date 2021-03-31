#!/bin/bash

repl(){
    clj -A:core:clj:test:repl
}

test-edit-nodejs(){
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

test-edit-jvm(){
    clojure \
        -A:core:edit-test:clj-test-runner \
        -m cognitect.test-runner \
        -d src/edit-test
}

test-edit-instaparse-nodejs(){
    clojure \
        -A:core:edit-instaparse-test:cljs-test-runner \
        -m cljs-test-runner.main \
        -d src/edit-instaparse-test \
        -x node
}

test-edit-instaparse-jvm(){
    clojure \
        -A:core:edit-instaparse-test:clj-test-runner \
        -m cognitect.test-runner \
        -d src/edit-instaparse-test
}

clean(){
    rm -rf .cpcache node_modules cljs-test-runner-out package-lock.json
}

"$@"