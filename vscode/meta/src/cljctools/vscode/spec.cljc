(ns cljctools.vscode.spec
  #?(:cljs (:require-macros [cljctools.vscode.spec]))
  (:require
   [clojure.spec.alpha :as s]))


(s/def ::tab-id (s/or :uuid uuid? :string string?))
(s/def ::tab-title string?)
(s/def ::tab-html-filepath string?)
(s/def ::tab-html-replacements (s/map-of string? string?))
(s/def ::tab-view-column any?)
(s/def ::tab-active? boolean?)
(s/def ::tabs (s/map-of some? some?))
(s/def ::cmd-id string?)
(s/def ::cmd-ids (s/coll-of ::cmd-id))
(s/def ::info-msg string?)

(s/def ::filepath string? )
(s/def ::dirpath string?)
(s/def ::ns-symbol symbol?)
(s/def ::filenames (s/coll-of string?))
(s/def ::file-content string?)