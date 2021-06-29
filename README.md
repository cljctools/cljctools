# cljctools
jvm, nodejs, js common abstractions to write cljc code


## cljctools/edit
library for editing clojure code

## why

- an editor extension like cljctools/mult needs to format/color/edit clojure source files
- as of now (March 2021) every editor extension implements their own logic to select forms, color brackets, format etc.
- that shouldn't be the case - there's nothing editor/extension specific about editing clojure/edn code, it should be generic
- with edit library we should be able to create an generic edit process, pass editor specifics in options (abstracted via edit.protocols and streams,like tools.reader StringReader)
- right now a basic operation  - given source text/stream and position, select current s-expression - is not implemented in any lib, every extension does their own dance with travsersing delimiters and selecting forms; in words of Gandalf - "Thranduil, this is madness!"
- the choice right now is - to drop another extension-specific dir in cljctools/mult and start tying the knot, or explicitly create a generic dependency and use it
- second option, please
- library should have spec and decribe the state of document as data, then extension imports it and using that data applies editor specific operations as needed
- what should work
  - form selection and editing (app using the lib asks "select current form at cursor", lib gives back a zipper or string, "move current form into the upper one" - new state of doc that can be applied to the underlying doc)
  - formatting as we type 
  - color tokens, brackets (as data decribing the document)
- it's about representing doc as zipper and data, and notifying app of changes: get source code/streams -> change internal state of edit -> notify of changes

## cljctools/bittorrent
bittorrent DHT libs

## cljctools/ipfs
ipfs and libp2p libs

## why

- <s>should be imlemented in cljc,</s> for jvm first and foremost: jvm is key, networking is key, that's why we need the jvm implementaion to be the best
- there is no need - simply by design of p2p networking - for libp2p to be in the browser

## goal

- one protocol, one encryption,...  - pick one (like bittorrent) and focus on user programs, not swiss army knife bloated tooling (MULTICS vs UNIX all over again..)

## cljctools/peerdb
peer-to-peer database, best of orbitdb and datomic

## goal

- should sync data over IFPS pubsub, like orbitdb, but have datomic interface and design (time travel not necessary)
- no need for orbits eventlog/docstore/feed etc. - like in datomic, eventlog is tranasaction log , else is high level abstraction for querying
- database data must be stored on IPFS: so that peers could use DHT, torrent-like benefits of downloading data peer-to-peer (partial and full, all that database writes is ipfs data)
- the need 
  - find, the app to find files on torrent and ipfs networks, needs for millions of peers to contribute to join index of files (torrents and ipfs)
  - index is replicated on each user computer, and apps continuously update the index (each app always searches/listens to DHT for new files or to update seeder count, and would write to index)
  -  app on first install downloads existing index (e.g. 2million entries) from peers, and user can query it locally, a complete database, with full text serach, with sort by, count etc.
  - orbitdb currently (0.26.1) is slow for writing data (even just for thousands of files), and requires the whole data to be loaded into memory (improvements are outlined in their roadmap)
  - what is needed to fast update/delete thousands of entries easily, load into memeory only what's needed, query language, datomic/dgraph RDF-like nature (data is attributes, with multiple indexes for fast querying) and in general be like a normal high level db, efficient and fast

## cljctools/http-repl
nrepl server should be an http server

## reason

- currenty (March 2021) nrepl server has it's own socket protocol and at least bencode and edn transports
- since this is a socket, we have to manually add ids to operations with send, and then manually group incoming messages - basically, re-inveting the wheel of request-response which is already solved by most widely used HTTP protocol, which does suppport streams as reposnse
- so to talk to nrepl server we first need an nrepl client that would gather responses per request
- and - we need a bencode implementation for our runtime that works with nrepl
- for example
  - right now cljctools/mult needs to communicate with nrepl servers of shadow-cljs, lein etc., which chose bencode(not edn) as their transport
  - so for mult to make requests to them, we're making cljctools/nrepl-client and there is no (every tool has its own) cljc bencode solution (only clj)
- one way to look it at
  1. make nrepl-client that turns socket communication into request-response
  2. make cljc bencode implementation
- however, this is already solved by HTTP:
  - request/reponse and request/stream-response are supported
  - Content-Type header allows client to specify which format the response should be
- so nrepl server should by design be HTTP with content neogtiation, and there would be no need for nrepl-client or cljc bencode
- requests should have streams as reponse
