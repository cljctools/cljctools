(ns cljctools.self-hosted.compiler.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [cljs.js :as cljs]
   [cljs.env :as env]
   [cljs.reader :refer [read-string]]
   [shadow.cljs.bootstrap.browser :as boot]

   [cljctools.self-hosted.protocols :as p]))

(defn create-compiler
  []
  (let [compile-state-ref (env/default-compiler-env)]
    (reify
      p/Compiler
      (-init [_ opts] (let [c| (chan 1)]
                        (boot/init compile-state-ref
                                   opts
                                   (fn []
                                     (println ::init)
                                     (let [eval cljs.core/*eval*]
                                       (set! cljs.core/*eval*
                                             (fn [form]
                                               (binding [cljs.env/*compiler* compile-state-ref
                                                         *ns* (find-ns cljs.analyzer/*cljs-ns*) #_(find-ns 'mult.extension)
                                                         cljs.js/*eval-fn* cljs.js/js-eval]
                                                 (eval form)))))
                                     (close! c|)))
                        c|))
      (-eval-data [_ opts])
      (-eval-str [_ opts] (let [{:keys [::code ::namespace]} opts
                                   c| (chan 1)]
                               (cljs/eval-str
                                compile-state-ref
                                code
                                "[test]"
                                {:eval cljs/js-eval
                                 :ns namespace
                                 :load (partial boot/load compile-state-ref)}
                                (fn [result]
                                  (put! c| result #(close! c|))))
                               c|))
      (-compile-js-str [_ opts]))))

(defn init
  [compiler opts]
  (p/-init compiler opts))

(defn eval-data
  [compiler opts]
  (p/-eval-data compiler opts))

(defn eval-str
  [compiler opts]
  (p/-eval-str compiler opts))

(comment

  (def compiler (create-compiler))

  (go
    (<! (init
         compiler
         {:path "/rovers2/js-out/scenario-bootstrap"
          :load-on-init '#{github.sergeiudris.rovers.scenario.main
                           clojure.core.async}})))

  (eval '(let [x 3]
           x))
  (eval '(let [x (fn [] 3)]
           x))
  (eval '(fn []))

  (eval '(println ::foo))

  ; nice, outputs :github.sergeiudris.rovers.scenario.main/foo
  (eval-str compiler {::namespace 'github.sergeiudris.rovers.scenario.main
                      ::code "(println ::foo)"}) 
  
  (eval-str compiler {::namespace 'github.sergeiudris.rovers.scenario.main
                      ::code "
                              (scenario.chan/op
                               {::op.spec/op-key ::scenario.chan/move-rovers
                                ::op.spec/op-type ::op.spec/fire-and-forget}
                               channels
                               {::scenario.spec/choose-location ::scenario.spec/closest
                                ::scenario.spec/location-type ::scenario.spec/signal-tower
                                ::scenario.spec/x-offset nil
                                ::scenario.spec/y-offset nil})
                              "})
  
  


  ;;
  )

(comment

  (eval '(let [x 3]
           x))
  (eval '(let [x (fn [] 3)]
           x))
  (eval '(fn []))

  (def f (eval '(fn [file-uri] (cljs.core/re-matches #".+\.cljs" file-uri))))
  (f "abc.cljs")

  (eval '(re-matches #".+clj" "abc.clj"))

  (eval '{:iden {:type :shadow-cljs
                 :runtime :cljs
                 :build :extension}
          :include (fn [file-uri]
                     (cljs.core/re-matches ".+.cljs" file-uri))
          :conn :mult})

  (read-string (str '{:iden {:type :shadow-cljs
                             :runtime :cljs
                             :build :extension}
                      :include '(fn [file-uri]
                                  (cljs.core/re-matches ".+.cljs" file-uri))
                      :conn :mult}))

  ;;
  )


(comment

  (def mult-edn-str
    (-> (.readFileSync fs "/home/user/code/mult/examples/fruits/.vscode/mult.edn") (.toString)))
  (def mult-edn (read-string mult-edn-str))
  (type (get-in mult-edn [:repls :ui :pred/include-file?]))
  (type '(fn [x] #{x}))
  (def f (eval (get-in mult-edn [:repls :ui :pred/include-file?])))
  (f "/fruits/system/src/banana.cljs") ; => works
  (type (re-pattern ".+.cljs"))
  (type #".+.cljs")

  (re-matches (re-pattern ".+\\.clj(s|c)") "/fruits/system/src/banana.cljc")


  ;;
  )

(comment

  (def tmp1 (atom 0))

  (defn bar [])


  (def ^:dynamic *extension-compiler* (compiler.api/create-compiler))

  (def ^:dynamic *solution-compiler* (compiler.api/create-compiler))


  #_(<! (compiler.api/init
         *extension-compiler*
         {:path "/home/user/code/deathstar/build/extension/resources/out/deathstar-bootstrap"
          :load-on-init '#{deathstar.main clojure.core.async}}))
  #_(<! (compiler.api/init
         *solution-compiler*
         {:path "/home/user/code/deathstar/build/resources/out/deathstar-bootstrap-solution-space"
          :load-on-init '#{deathstar.tabapp.solution-space.main
                           clojure.core.async}}))
  #_(prn (self-hosted.api/test1))


  (def a 'hello)
  (-> #'a meta)

  (binding [*ns* 'foo]
    (pr-str
     `(do
        (type type)
        (bar)
        ~a)))

  (take! (compiler.api/eval-str
          *solution-compiler*
          {:code
           "
            (cljs.core/type cljs.core/type)
            #_(cljs.core/type deathstar.tabapp.solution-space.main/state)
    "
           :nspace 'deathstar.tabapp.solution-space.main})
         (fn [data]
           (prn data)
           (prn (type (:value data)))))

  (take! (compiler.api/eval-str
          *extension-compiler*
          {:code
           "
  (cljs.core/type deathstar.main/main)
            
    "
           :nspace 'deathstar.main})
         (fn [data]
           (prn data)
           (prn (type (:value data)))))

  (take! (compiler.api/eval-str
          *extension-compiler*
          {:code
           "
       #_(cljs.core/type deathstar.extension/tmp1)
            (type deathstar.extension/tmp1)
             (swap! deathstar.extension/tmp1 inc)
            #_@deathstar.extension/tmp1
            @tmp1
            
            
    "
           :nspace 'deathstar.extension})
         (fn [data]
           (prn data)
           (prn (type (:value data)))))


  ;;
  )