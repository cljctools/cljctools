deps-tool
a tool allowing js- and -jvm languages to install clj/cljs/cljc + deps.edn code from github, like golang and clojure do 

[merged into https://github.com/cljctools/cljctools]

rationale

- with golang and clojure we can require code direclty from github, for example
{:deps {clj.native-image/clj.native-image
             {:git/url "https://github.com/taylorwood/clj.native-image.git"
              :sha "7708e7fd4572459c81f6a6b8e44c96f41cdd92d4"}}}
- now, we can write for example cljctools/paredit libary as .cljc and use it in our clojure(script) projects, just like golang does, awesome
- but how do javascript and jvm based project use our foo/bar library? 
- we could build two artifacts - jar and npm dist - and publish to maven, clojars AND npm
- we would need accounts, keys etc., which works
- but... we already see with golang that using code direclty from github and compiling it on site - Dockerfile or our OS - is less effiecient, but more freeing and makes writing code more enjoyable - any commit can be used as a dep
- we want a tool - a deps-tool - to invite javascript and jvm people to the party we (clojure) and golang are already having
- deps-tool:
  - is a cli tool, it is released to github releases (or better - is a bash script) that we download - in Dockerfile or our OS (just like a lein,nodejs, npm,...)
  - in our project (js or jvm based), we add a deps.edn and/or deps-tool.edn and specify - our project also depends on github.com/foo/bar clj/cljs/cljc lib 
  - we run the usual npm install or mvn isntall - we get our node_modules or ~/.m2, nice
  - then we run deps-tool install - it does what deps.edn does, get the code etc., and also compiles the lib into a .jar or npm module and puts the artifact in node_modules or ~/.m2
  - so clj/cljs/cljc code can be used direclty from github by js- jvm- based projects, a TWO WAY STREET ('cause we already can use npm/maven)
