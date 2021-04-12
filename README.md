# libp2p
clojure(script) implementation of https://github.com/libp2p

## deprecated

- launch or use existing running network node (ipfs node) for networking via http-api
- app requests pubsub stream or uses p2p http proxy
- app and networking are decoupled via http api (given node can do that, and ipfs node can)

## why

- need for jvm implementation of libp2p with examples and AutoRelay
- should be structured and have docs like https://github.com/metosin/reitit, execpt with `deps.edn` to import direcly from github via commit hash
- most code should be in `.cljc`, first imlementation in `.clj` and `nodejs` port - if needed, because already solved by js-libp2p - added later
- doubtful there is a need - simply by design of p2p networking - for libp2p to be in the browser, but can be added
- jvm is key, networking is key, that's why we need the jvm implementaion to be the best
