# nrepl-client
nrepl client

## deprecated

- the main purpose of cljctools/nrepl-client was to allow https://github.com/cljctools/mult to talk to nrepl servers
- but a better solution is to build a new standard, http based nrepl https://github.com/cljctools/http-repl/tree/76a3332c9d6d15470b66c210f7655ad838e073e6#reason
- to support current standard - nrepl - mult will use existing nodejs/cljs tools, with efforts being put to better use, including http-repl
