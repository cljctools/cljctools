# iam
a library to handle user sing-in, identity etc. in an app (using DIDs)

## rationale

- reusable: when building our app, we want everything-identity to be a reusalbe 'plugin'/layer/lib
- similar for example to how https://github.com/metosin/reitit solves http service with multiple choice of servers, async librarries etc.
- db of choice: have a transport/adapter for that db (adapter is a list of fns for read and write IAM ops, translates how to put/retrieve that data to/from db of choice)
- http server of choice: we want to use reitit at least (it has all the ingridients - route data, spec validation, decoupled design,..)
- ui included: just like with `reitit` that comes with (or generates) swagger ui, identity lib should come with ui
  - https://github.com/metosin/reitit/blob/0.5.12/modules/reitit-swagger-ui/project.clj#L14
  - https://github.com/metosin/ring-swagger-ui
- no providers, use self hosted iam + decentralized identity:
  - "login with" requires app registration, when we want decentralized apps that run from code
  - what ory does is a lot more, we need self hosted iam and **use DIDs**
- just an embedded process: 
  - like we start jetty or aleph, iam should be just a process we start on host port
  - or - use it's modules as library (`reitit` is exactly an example of how)
- a *library*:
  - it should be like `ory` but a library instead, like `reitit`, with http adapters of choice
- <ins>can we extend `reitit`? identity middleware/interceptors?</ins> that would be awesome

## already out there

- https://github.com/ory
- https://github.com/decentralized-identity