(ns cljctools.vscode.protocols)

(defprotocol Connect
  (-connect [_])
  (-disconnect [_])
  (-connected? [_]))

(defprotocol Send
  (-send [_ v]))

(defprotocol Release
  :extend-via-metadata true
  (-release [_]))

(defprotocol Active
  (-active? [_]))

(defprotocol Host
  (-register-commands [_ commands])
  (-create-tab [_ tabid])
  (-read-workspace-file [_ filepath])
  (-show-info-msg [_ msg])
  (-join-workspace-path [_ subpath]))

(defprotocol Editor
  (-selection [_])
  (-active-ns [_] "nil if it's not clj file"))

