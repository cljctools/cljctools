(ns cljctools.bittorrent.bencode.core)

(defn char-code [chr] (.charCodeAt chr 0))

(def ^:const colon-int (char-code \:))
(def ^:const i-int (char-code \i))
(def ^:const e-int (char-code \e))
(def ^:const l-int (char-code \l))
(def ^:const d-int (char-code \d))

(defprotocol IPushbackInputStream
  (read* [_] [_ offset length])
  (unread* [_ char-int]))

(deftype PushbackInputStream [buffer ^:mutable offset]
  IPushbackInputStream
  (read*
    [_]
    (if (>= offset (.-length buffer))
      -1
      (let [char-int (.readUint8 buffer offset)]
        (set! offset (inc offset))
        char-int)))
  (read*
    [_ off length]
    (if (>= offset (.-length buffer))
      -1
      (let [start (+ offset off)
            end (+ start length)
            buf (.subarray buffer start end)]
        (set! offset (+ offset length))
        buf)))
  (unread* [_ char-int]
    (set! offset (dec offset))))

(defprotocol IOutputStream
  (write* [_ data])
  (to-buffer* [_])
  (reset* [_]))

(deftype OutputStream [arr]
  IOutputStream
  (write*
    [_ data]
    (cond
      (int? data)
      (.push arr (doto (js/Buffer.allocUnsafe 1) (.writeInt8 data)))

      (instance? js/Buffer data)
      (.push arr data)

      (string? data)
      (.push arr (js/Buffer.from data "utf8"))))
  (reset*
    [_]
    (.splice arr 0))
  (to-buffer*
    [_]
    (js/Buffer.concat arr)))

(defmulti encode*
  (fn
    ([data out]
     (cond
       (instance? js/Buffer data) ::buffer
       (number? data) ::number?
       (string? data) ::string?
       (keyword? data) ::keyword?
       (map? data) ::map?
       (sequential? data) ::sequential?))
    ([data out dispatch-val]
     dispatch-val)))

(defmethod encode* ::number?
  [num out]
  (write* out i-int)
  (encode* (.toString num) out ::string?)
  (write* out e-int))

(defmethod encode* ::string?
  [string out & args]
  (encode* (js/Buffer.from string "utf8") out ::buffer))

(defmethod encode* ::keyword?
  [kword out]
  (encode* (name kword) out ::string?))

(defmethod encode* ::sequential?
  [coll out]
  (write* out l-int)
  (doseq [item coll]
    (encode* item out))
  (write* out e-int))

(defmethod encode* ::map?
  [amap out]
  (write* out d-int)
  (doseq [[k v] (into (sorted-map) amap)]
    (encode* k out)
    (encode* v out))
  (write* out e-int))

(defmethod encode* ::buffer
  [buffer out]
  (write* out (-> (.-length buffer) (.toString)))
  (write* out colon-int)
  (write* out buffer))

(defn encode
  [data]
  (let [out (OutputStream. #js [])]
    (encode* data out)
    (to-buffer* out)))

(defn peek-next
  [in]
  (let [char-int (read* in)]
    (when (= -1 char-int)
      (throw (ex-info (str ::decode* " unexpected end of InputStream") {})))
    (unread* in char-int)
    char-int))

(defmulti decode*
  (fn
    ([in out]
     (condp = (peek-next in)
       i-int :integer
       l-int :list
       d-int :dictionary
       :else :buffer))
    ([in out dispatch-val]
     dispatch-val)))

(defmethod decode* :dictionary
  [in
   out
   & args]
  (read* in) ; skip d char
  (loop [result (transient [])]
    (let [char-int (peek-next in)]
      (cond

        (= char-int e-int) ; return
        (do
          (reset* out)
          (apply hash-map (persistent! result)))

        (= char-int i-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got integer") {})
          (recur (conj! result  (decode* in out :integer))))

        (= char-int d-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got dictionary") {})
          (recur (conj! result  (decode* in out :dictionary))))

        (= char-int l-int)
        (if (even? (count result))
          (ex-info (str ::decode*-dictionary " bencode keys must be strings, got list") {})
          (recur (conj! result  (decode* in out :list))))

        :else
        (let [next-element-buffer (decode* in out :buffer)
              next-element (if (even? (count result))
                             #_its_a_key
                             (.toString next-element-buffer "utf8")
                             #_its_a_value
                             next-element-buffer)]
          (recur (conj! result next-element)))))))

(defmethod decode* :list
  [in
   out
   & args]
  (read* in) ; skip l char
  (loop [result (transient [])]
    (let [char-int (peek-next in)]
      (cond

        (= char-int e-int) ; return
        (do
          (reset* out)
          (persistent! result))

        (= char-int i-int)
        (recur (conj! result  (decode* in out :integer)))

        (= char-int d-int)
        (recur (conj! result  (decode* in out :dictionary)))

        (= char-int l-int)
        (recur (conj! result  (decode* in out :list)))

        :else
        (recur (conj! result (decode* in out :buffer)))))))

(defmethod decode* :integer
  [in
   out
   & args]
  (read* in) ; skip i char
  (loop []
    (let [char-int (read* in)]
      (cond

        (= char-int e-int)
        (let [number-string (->
                             (to-buffer* out)
                             (.toString "utf8"))
              value (try
                      (js/Number.parseInt number-string)
                      (catch js/Error e
                        (js/Number.parseFloat number-string)))]
          (reset* out)
          value)

        :else (do
                (write* out char-int)
                (recur))))))

(defmethod decode* :buffer
  [in
   out
   & args]
  (let []
    (loop []
      (let [char-int (read* in)]
        (cond

          (= char-int colon-int)
          (let [size (-> (to-buffer* out)
                         (.toString "utf8")
                         (js/Number.parseInt))
                buffer (read* in 0 size)]
            (reset* out)
            buffer)

          :else (do
                  (write* out char-int)
                  (recur)))))))

(defn decode
  [buffer]
  (let [in (PushbackInputStream. buffer 0)
        out (OutputStream. #js [])]
    (decode* in out)))


(comment

  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      cljctools.bittorrent/bencode-js {:local/root "./bittorrent/src/bencode-js"}}} '\
  -M -m cljs.main --repl-env node --watch "bittorrent/src/bencode-js" --compile cljctools.bittorrent.bencode.core --repl

  (require '[cljctools.bittorrent.bencode.core :refer [encode decode]])

  (let [data {:t (js/Buffer.from "aabbccdd" "hex")
              :a {"id" (js/Buffer.from "197957dab1d2900c5f6d9178656d525e22e63300" "hex")}}]
    (->
     (encode data)
     #_(.toString)
     (decode)
     (-> (get-in ["a" "id"]))
     (.toString "hex")))


  ;
  )

