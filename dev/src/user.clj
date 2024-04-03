(ns user
  (:require [clojure.spec.alpha :as spec]
            [clojure.spec.test.alpha :as spec.test]
            [cognitect.transcriptor :as xr]
            [rad.api :as rad]
            [rad.log :as log]))

;; Arcane incantation
(defmethod print-method (Class/forName "[B")
  [^bytes v ^java.io.Writer w]
  ;; Print byte arrays as UTF-8 strings.
  ;;
  ;; Not all byte arrays represent UTF-8 strings, obviously, but it might be
  ;; a more useful default than something like #object["[B" 0x30db2bf9
  ;; "[B@30db2bf9"] when working with Redis at the REPL.
  (.write w "#b ")
  (binding [*print-readably* true]
    (print-method (String. v "UTF-8") w)))

(defn transcribe
  ([] (transcribe {:path ["test"]}))
  ([{:keys [path] :or {path "test"}}]
   (run! xr/run (xr/repl-files path))))

(comment
  (spec/check-asserts true)
  (require '[clojure.repl.deps :as deps])
  (deps/add-lib 'expound/expound)
  (require '[expound.alpha :as expound])
  (set! spec/*explain-out* expound/printer)
  (spec.test/instrument)

  (log/console-logging! :level :finest)

  (transcribe)

  (def redis (rad/client))
  (redis)

  @(redis ^{:cb (bound-fn* prn)} [:SUBSCRIBE "channel-1"])
  @(redis [:PUBLISH "channel-1" "Hello, world!"])
  @(redis [:COMMAND :DOCS])

  @(redis [:CLIENT :LIST])
  @(redis [:MEMORY :STATS])
  ,,,)
