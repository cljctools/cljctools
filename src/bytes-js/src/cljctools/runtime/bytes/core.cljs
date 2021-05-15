(ns cljctools.runtime.bytes)

; requires js/Buffer

(defn runtime-bytes?
  [x]
  (instance? js/Buffer x))

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

(defn pushback-input-stream
  [source]
  (PushbackInputStream. source 0))

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

(defn output-stream
  []
  (OutputStream. #js []))

(defn char-code 
  [chr] 
  (.charCodeAt chr 0))