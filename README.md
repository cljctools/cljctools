# libp2p
clojure implementation of libp2p

## why

- need for jvm implementation of libp2p with examples and AutoRelay
- should be structured and have docs like https://github.com/metosin/reitit, execpt with `deps.edn` to import direcly from github via commit hash
- should be imlementated for jvm
- there is no need - simply by design of p2p networking - for libp2p to be in the browser
- no need for nodejs either - if we really want to build desktop peer-to-peer apps for millions of users, then multithreading, db and networking as libs in one runtime matter
- jvm is key, networking is key, that's why we need the jvm implementaion to be the best
