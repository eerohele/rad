(ns ^:no-doc rad.log
  "Logging via java.util.logging.

  EXPERIMENTAL; subject to change or removal."
  (:require [clojure.set :as set])
  (:import (java.util Date)
           (java.util.logging ConsoleHandler Formatter Handler Logger Level LogRecord)))

(set! *warn-on-reflection* true)

(def ^Level keyword->level
  {:finest Level/FINEST
   :finer Level/FINER
   :fine Level/FINE
   :info Level/INFO
   :warning Level/WARNING
   :severe Level/SEVERE})

(def level->keyword
  (set/map-invert keyword->level))

(defn ^:private edn->str
  [x]
  (binding [*print-length* nil
            *print-level* nil
            *print-dup* false
            *print-meta* false
            *print-readably* true
            *print-namespace-maps* false]
    (pr-str x)))

(def edn-formatter
  (proxy [Formatter] []
    (format [^LogRecord record]
      (let [msg (-> record .getParameters first)]
        (->
          (assoc msg
            :ns (.getSourceClassName record)
            :thread-id (.getLongThreadID record)
            :inst (-> record .getInstant Date/from)
            :level (-> record .getLevel level->keyword)
            :seq-no (.getSequenceNumber record))
          (edn->str)
          (str \newline))))))

(def console-handler
  (doto (ConsoleHandler.) (.setFormatter edn-formatter)))

(defonce ^Logger logger
  (doto
    (Logger/getLogger "rad.api")
    (.setUseParentHandlers false)
    (.setLevel Level/FINEST)))

(defmacro log
  [level event data]
  (let [{:keys [line column]} (meta &form)
        ns (-> *ns* ns-name name)]
    `(let [level# (keyword->level ~level)]
       (when (.isLoggable logger level#)
         (let [log-record#
               (doto (LogRecord. level# (pr-str ~event))
                 (cond-> (instance? Exception ~data) (.setThrown ~data))
                 (.setSourceClassName ~ns)
                 (.setParameters (object-array [{:line (or ~line -1)
                                                 :column (or ~column -1)
                                                 :event ~event
                                                 :data ~data}])))]
           (.log logger log-record#))))))

(defn ^:experimental console-logging!
  "Log to console.

  EXPERIMENTAL; subject to change or removal."
  [& {:keys [level]}]
  (.addHandler logger console-handler)
  (.setLevel ^Handler console-handler (keyword->level level)))

(comment
  (console-logging! :level :finer)

  (log :info :foo {:bar :baz})
  (log :finest :foo {:bar :baz})
  (log :severe :foo {:bar :baz})
  (log :fine :foo {:bar :baz})

  (log :severe :foo (Throwable.))
  ,,,)
