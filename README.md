# http-repl
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
