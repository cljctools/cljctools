# cljs-self-hosting
common api for self-hosting clojurescript with cljs.js or shadow-cljs

## deprecated 

- [mult](https://github.com/cljctools/mult) is no longer using cljctools/cljs-self-hosting, transitioned to https://github.com/borkdude/sci
- source files in [examples/mult](./examples/mult) are kept as a reference, correspond to [mult at c6ad2d7c50](https://github.com/cljctools/mult/tree/c6ad2d7c50ff818f6d40310cc4a4343bb56e6f9c), when cljs-self-hosting was used for evaluation

## purpose

- primarily for https://github.com/cljctools/mult
- eval is need on nodejs to allow users to specify predicate functions in `mult.edn`
- if we compile 2 vscode extension with shadow-cljs (as of now version is 2.11.7), they cannot run simultenously (smth with not being able to find cljs fns)
- but if we compile directly with cljs, we can run multiple extensions
- but we want all the awesomeness - REPL, builds - of shadow-cljs
- so for dev we use shadow, but for release - cljs
- but: they have different ways of providing clojurescript self-hosting
- that's why we need a dep - and it should be generic - so we can switch deps.edn :alias and: during dev self-hosting namespace resovles to shadow-cljs, during release it resolves to cljs
- it works, this libabry is an extraction of this genetic logic out from mult
