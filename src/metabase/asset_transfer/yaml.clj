(ns metabase.asset-transfer.yaml
  "Bounded YAML documents with no aliases, merge keys or custom tags."
  (:require
   [clj-yaml.core :as yaml]
   [metabase.asset-transfer.schema :as s])
  (:import
   (java.io StringReader)
   (java.nio.charset StandardCharsets)
   (org.yaml.snakeyaml.events AliasEvent CollectionStartEvent ScalarEvent)))

(set! *warn-on-reflection* true)

(defn parse
  "Parse one asset document. Parser diagnostics never expose file contents."
  [text]
  (s/check! (and (string? text) (<= (alength (.getBytes ^String text StandardCharsets/UTF_8)) s/max-bytes))
            "FILE_TOO_LARGE" [] "Expected a YAML file of at most 10 MiB.")
  (try
    (let [options {:allow-duplicate-keys false :max-aliases-for-collections 0
                   :nesting-depth-limit 80 :code-point-limit s/max-bytes}
          parser (yaml/make-yaml options)]
      (doseq [event (.parse parser (StringReader. text))]
        (when (or (instance? AliasEvent event)
                  (and (instance? ScalarEvent event)
                       (or (.getTag ^ScalarEvent event) (= "<<" (.getValue ^ScalarEvent event))))
                  (and (instance? CollectionStartEvent event) (.getTag ^CollectionStartEvent event)))
          (s/fail! "INVALID_YAML" [] "YAML aliases, merge keys and explicit tags are not supported.")))
      (s/wire (yaml/parse-string text options)))
    (catch Exception _
      (s/fail! "INVALID_YAML" [] "Invalid YAML: use one document without duplicate keys, aliases or tags."))))

(defn generate
  "Encode an asset document as readable YAML."
  [document]
  (yaml/generate-string document :dumper-options {:flow-style :block}))
