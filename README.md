# socket
clojure(script) sockets abstracted as core.async channels

- sockets have slightly different api but same idea: a connection, receive/send data
- connections should be decoupled from other things via channels
- is `send` and `recv` channels: e.g. cljctools/nrepl-client does nrepl logic, but it only knows `send` and `recv` channels, unaware of how data comes and goes
- should expose `mult` of `recv`, `evt` channel: like with nrepl-client, so same connection can be used by multiple nrepls(things) or we can tap into `evt` channel
- jvm, nodejs, browser same socket api: we import `cljctools.socket` thing and also pick a very short specific code from one of `cljctools.socket.nodejs-net`, `cljctools.socket.ws` and pass it as opts, like `(cljctools.socket.start (merge {...opts} cljctools.socket.nodejs-net/opts)`