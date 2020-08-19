(ns cljctools.vscode.api
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string :refer [format]]
   [goog.object]
   [clojure.string :as string]
   [cljs.reader :refer [read-string]]
   #_["fs" :as fs]
   #_["path" :as path]
   [cognitect.transit :as t]

   [cljctools.vscode.protocols :as p]
   [cljctools.vscode.spec :as sp]
   [cljctools.paredit :as paredit]
   [cljs.nodejs :as node]))

(def fs (node/require "fs"))
(def path (node/require "path"))

(def ^:const NS_DECL_LINE_RANGE 100)

(declare vscode)
(try
  (def vscode (node/require "vscode"))
  (catch js/Error e (do (println "Error on (node/require vscode)") (println e))))

(comment

  (exists? js/global)
  (type js/global)
  js/global.process.versions

  (def worker-threads (node/require "worker_threads"))
  (type worker-threads.isMainThread)
   

  ;;
  )


(defn show-information-message*
  [vscode msg]
  (.. vscode.window (showInformationMessage msg)))

(defn register-command*
  [{:keys [id vscode context on-cmd] :as ops}]
  (let [disposable (.. vscode.commands
                       (registerCommand
                        id
                        (fn [& args]
                          (on-cmd id args))))]
    (.. context.subscriptions (push disposable))))

(defn register-commands*
  [{:keys [ids vscode context on-cmd] :as ops}]
  (let []
    (doseq [id ids]
      (register-command* {:id id
                          :vscode vscode
                          :context context
                          :on-cmd on-cmd}))))

(defn read-workspace-file
  [filepath callback]
  (let []
    (as-> nil o
      (.join path vscode.workspace.rootPath filepath)
      (vscode.Uri.file o)
      (.readFile vscode.workspace.fs o)
      (.then o callback))))

; https://stackoverflow.com/a/41029103/10589291

(defn js-lookup
  [obj]
  (-> (fn [result key]
        (let [v (goog.object/get obj key)]
          (if (= "function" (goog/typeOf v))
            result
            (assoc result key v))))
      (reduce {} (.getKeys goog/object obj))))

(defn js-lookup-nested
  [obj]
  (if (goog.isObject obj)
    (-> (fn [result key]
          (let [v (goog.object/get obj key)]
            (if (= "function" (goog/typeOf v))
              result
              (assoc result key (js-lookup-nested v)))))
        (reduce {} (.getKeys goog/object obj)))
    obj))

(comment

  vscode.workspace.rootPath

  vscode.workspace.workspaceFile
  vscode.workspace.workspaceFolders

  (as-> nil o
    (.join path vscode.workspace.rootPath ".vscode")
    (vscode.Uri.file o)
    (.readDirectory vscode.workspace.fs o)
    (.then o (fn [d] (println d))))

  (as-> nil o
    (.join path vscode.workspace.rootPath ".vscode/mult.edn")
    (vscode.Uri.file o)
    (.readFile vscode.workspace.fs o)
    (.then o (fn [d] (println d))))

  ; https://code.visualstudio.com/api/references/vscode-api#TextDocument
  ; https://code.visualstudio.com/api/references/vscode-api#Selection
  ; https://code.visualstudio.com/api/references/vscode-api#Range

  (if  vscode.window.activeTextEditor
    vscode.window.activeTextEditor.document.uri
    :no-active-editor)

  (println vscode.window.activeTextEditor.selection)

  (js-lookup-nested vscode.window.activeTextEditor.selection)

  (do
    (def start vscode.window.activeTextEditor.selection.start)
    (def end vscode.window.activeTextEditor.selection.end)
    (def range (vscode.Range. start end))
    (def text (.getText vscode.window.activeTextEditor.document range)))

  ;;
  )

(defn parse-ns
  "Safely tries to read the first form from the source text.
   Returns ns name or nil"
  [filename text log]
  (try
    (when (re-matches #".+\.clj(s|c)?" filename)
      (let [fform (read-string text)]
        (when (= (first fform) 'ns)
          (second fform))))
    (catch js/Error ex (log "; parse-ns error " {:filename filename
                                                 :err ex}))))

(defn active-ns
  [text-editor log]
  (when text-editor
    (let [range (vscode.Range.
                 (vscode.Position. 0 0)
                 (vscode.Position. NS_DECL_LINE_RANGE 0))
          text (.getText text-editor.document range)
          filepath text-editor.document.fileName
          ns-sym (parse-ns filepath text log)
          data {:filepath filepath
                :ns-sym ns-sym}]
      data
      #_(prn text-editor.document.languageId))))

#_(defn tabapp-html
    [{:keys [vscode context panel script-path html-path script-replace] :as opts}]
    (def panel panel)
    (let [script-uri (as-> nil o
                       (.join path context.extensionPath script-path)
                       (vscode.Uri.file o)
                       (.asWebviewUri panel.webview o)
                       (.toString o))
          html (as-> nil o
                 (.join path context.extensionPath html-path)
                 (.readFileSync fs o)
                 (.toString o)
                 (string/replace o script-replace script-uri))]
      html))

#_(defn make-panel
    [vscode context id handlers]
    (let [panel (vscode.window.createWebviewPanel
                 id
                 "mult tab"
                 vscode.ViewColumn.Two
                 #js {:enableScripts true
                      :retainContextWhenHidden true})]
      (.onDidDispose panel (fn []
                             ((:on-dispose handlers) id)))
      (.onDidReceiveMessage  panel.webview (fn [msg]
                                             (let [data (read-string msg)]
                                               ((:on-message handlers) id data))))
      (.onDidChangeViewState panel (fn [panel]
                                     ((:on-state-change handlers) {:active? panel.active})))

      panel))

(defn create-tab*
  [{:keys [context
           id title view-column
           script-path html-path script-replace
           tab-msg| tab-state|]
    :as opts
    :or {id (random-uuid)
         title "Default title"
         script-path "resources/out/tabapp.js"
         html-path "resources/index.html"
         script-replace "/out/tabapp.js"
         view-column vscode.ViewColumn.Two
         }}]
  (let [{:keys [on-message on-dispose on-state-change]
         :or {on-message (fn [id data] (put! tab-msg| (assoc data :tab/id id)))
              on-dispose (fn [id] (put! tab-state| (sp/vl :tab-state| {:op :host/tab-disposed :host.tab/id id})))
              on-state-change (fn [data] (prn data))}} opts
        panel (vscode.window.createWebviewPanel
               id
               title
               view-column
               #js {:enableScripts true
                    :retainContextWhenHidden true})
        _ (do (.onDidDispose panel (fn []
                                     (on-dispose id)))
              (.onDidReceiveMessage  panel.webview (fn [msg]
                                                     (let [data (read-string msg)]
                                                       (on-message id data))))
              (.onDidChangeViewState panel (fn [panel]
                                             (on-state-change {:active? panel.active}))))
        script-uri (as-> nil o
                     (.join path context.extensionPath script-path)
                     (vscode.Uri.file o)
                     (.asWebviewUri panel.webview o)
                     (.toString o))
        html (as-> nil o
               (.join path context.extensionPath html-path)
               (.readFileSync fs o)
               (.toString o)
               (string/replace o script-replace script-uri))
        lookup opts]
    (set! panel.webview.html html)
    (reify
      p/Send
      (-send [_ v] (.postMessage (.-webview panel) (pr-str v)))
      p/Release
      (-release [_] (println "release for tab not implemented"))
      p/Active
      (-active? [_] panel.active)
      cljs.core/ILookup
      (-lookup [_ k] (-lookup _ k nil))
      (-lookup [_ k not-found] (-lookup lookup k not-found)))))

(defn create-channels
  []
  (let [host-ops| (chan 10)
        host-ops|m (mult host-ops|)
        host-evt| (chan 10)
        host-evt|m (mult host-evt|)
        host-evt|x (mix host-evt|)
        ;; host|p (pub (tap host|m (chan 10)) sp/TOPIC (fn [_] 10))
        ]
    {:host-ops| host-ops|
     :host-ops|m host-ops|m
     :host-evt| host-evt|
     :host-evt|m host-evt|m
     :host-evt|x host-evt|x
    ;;  :host|p host|p
     }))

(defn create-proc-host
  [channels ctx]
  (let [{:keys [host-ops|m host-evt|]} channels
        host-ops|t (tap host-ops|m (chan 10))
        release #(do
                   (untap host-ops|m  host-ops|t)
                   (close! host-ops|t))
        state (atom (select-keys ctx [:context]))]
    (do
      (.onDidChangeActiveTextEditor vscode.window (fn [text-editor]
                                                    (let [data (active-ns text-editor prn)]
                                                      (when data
                                                        (prn ".onDidChangeActiveTextEditor")
                                                        #_(put! ops| (p/-vl-texteditor-changed ops|i data)))))))
    (go
      (loop []
        (when-let [v (<! host-ops|t)]
          (condp = (:op v)
            (sp/op :host-ops|
                   :host/extension-activate) (let [{:keys [context]} v]
                                               (prn "vscode.api :host/extension-activate")
                                               (swap! state assoc :context context)
                                               (put! host-evt| v)))
          (recur)))
      (println "; proc-host go-block exits"))
    (reify
      p/Release
      (-release [_] (release))
      p/Host
      (-show-info-msg [_ msg] (show-information-message* vscode msg))
      (-register-commands [_ opts]
        (let [{:keys [cmd| ids]} opts
              on-cmd (fn [id args]
                       #_(prn "on-cmd" id)
                       (put! cmd| (sp/vl :host-cmd| {:op id :args args})))]
          (register-commands* {:ids ids
                               :vscode vscode
                               :context (:context @state)
                               :on-cmd on-cmd})))
      (-create-tab [_ opts]
        (create-tab* (merge {:context (:context @state)} opts)))
      (-read-workspace-file [_ filepath]
        (let [c| (chan 1)]
          (read-workspace-file filepath (fn [file] (put! c| (.toString file)) (close! c|)))
          c|))
      (-join-workspace-path [_ subpath]
        (let [extpath (. (:context @state) -extensionPath)]
          (.join path extpath subpath)))
      p/Editor
      (-active-ns [_] (active-ns vscode.window.activeTextEditor prn))
      (-selection [_]
        (when  vscode.window.activeTextEditor
          (let [start vscode.window.activeTextEditor.selection.start
                end vscode.window.activeTextEditor.selection.end
                range (vscode.Range. start end)
                text (.getText vscode.window.activeTextEditor.document range)]
            text)))
      ILookup
      (-lookup [_ k] (-lookup _ k nil))
      (-lookup [_ k not-found] (-lookup @state k not-found)))))

; repl only
(def ^:dynamic *context* (atom nil))

(defn activate
  [{:keys [host-ops|] :as channels} context]
  (reset! *context* context)
  (prn "vscode.api makef-activate")
  (put! host-ops| {:op :host/extension-activate :context context}))

(defn deactivate
  [{:keys [host-ops|] :as channels}]
  (js/console.log "cljctools.vscode.api/makef-deactivate"))

(defn show-info-msg
  [host msg]
  (p/-show-info-msg host msg))

(defn register-commands
  [host {:keys [cmd| ids] :as opts}]
  (p/-register-commands host opts))

(defn create-tab
  [host opts]
  (p/-create-tab host opts))

(defn send-tab
  [tab v]
  (p/-send tab v))

(comment

  (read-string (str (ex-info "err" {:a 1})))


  (defn roundtrip [x]
    (let [w (t/writer :json)
          r (t/reader :json)]
      (t/read r (t/write w x))))

  (defn test-roundtrip []
    (let [list1 [:red :green :blue]
          list2 [:apple :pear :grape]
          data  {(t/integer 1) list1
                 (t/integer 2) list2}
          data' (roundtrip data)]
      (assert (= data data'))))

  ;;
  )