(ns ^:no-doc rad.specs
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as spec]
            [clojure.string :as string])
  (:import (java.time Duration)
           (java.util.concurrent CompletableFuture)))

(spec/def ::command
  (spec/and simple-keyword?
    (fn [command]
      (let [cmd-name (name command)]
        (and (= (string/upper-case cmd-name) cmd-name)
          (not= :MONITOR command))))))

(spec/def ::arg
  (spec/alt
    :bytes bytes?
    :string string?
    :ident ident?
    :number number?))

(spec/def ::cmdvec
  (spec/and vector?
    (spec/cat :command ::command :args (spec/* ::arg))))

(def ^:private disallowed-pipeline-cmds
  (sorted-set
    :PSUBSCRIBE
    :PUNSUBSCRIBE
    :SSUBSCRIBE
    :SUBSCRIBE
    :SUNSUBSCRIBE
    :UNSUBSCRIBE))

(defn ^:private only-pipelineable-commands?
  [cmdvecs]
  (let [cmd-names (into #{} (map :command) cmdvecs)]
    (empty?
      (set/intersection disallowed-pipeline-cmds cmd-names))))

(spec/def ::commands
  (spec/and
    (spec/coll-of ::cmdvec :min-count 1 :kind sequential?)
    (fn [cmdvecs]
      (or (= 1 (count cmdvecs))
        (only-pipelineable-commands? cmdvecs)))))

(spec/def ::host
  (spec/and string? (complement string/blank?)))

(spec/def ::port
  (spec/int-in 0 65536))

(spec/def ::decode fn?)

(spec/def ::send-timeout
  (partial instance? Duration))

(spec/def ::sendq-capacity
  pos-int?)

(spec/def ::options
  (spec/keys* :opt-un [::host ::port ::decode ::send-timeout ::sendq-capacity]))

(spec/def ::completable-future
  (partial instance? CompletableFuture))

(spec/def ::client
  (spec/fspec
    :args (spec/cat :commands (spec/nilable ::commands))
    :ret (spec/nilable ::completable-future)))

(comment
  (spec/explain ::commands [])
  (spec/explain ::commands [[]])
  (spec/explain ::commands [[""]])
  (spec/explain ::commands [[1]])
  (spec/explain ::commands [[:ECHO "foo"]])
  (spec/explain ::commands [[:ECHO "foo"] [:ECHO "bar"]])
  (spec/explain ::commands [[:INCR "foo" 1]])
  (spec/explain ::commands [[:subscribe "channel-1"]])
  (spec/explain ::commands [[:SUBSCRIBE "channel-1"] [:SUBSCRIBE "channel-2"]])
  (spec/explain ::commands [[:SUBSCRIBE "channel-1"]])
  ,,,)
