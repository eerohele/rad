(ns ^:no-doc rad.impl.resilience
  (:require [cognitect.anomalies :as-alias anomalies]
            [clojure.math :as math]
            [rad.impl.anomaly :as anomaly :refer [anomaly!]]
            [rad.log :refer [log]])
  (:import (java.time Duration)
           (java.util.concurrent ThreadLocalRandom)))

(defn calculate-delay-ms
  ^Duration [base-delay-ms cap-ms attempt]
  (let [delay-ms (min cap-ms (* base-delay-ms (math/pow attempt 2)))
        jitter-ms (.nextDouble (ThreadLocalRandom/current) 0 (* delay-ms 0.1))]
    (Duration/ofMillis (+ delay-ms jitter-ms))))

(comment (calculate-delay-ms 100 10000 10) ,,,)

(defn sleep
  [^Duration duration]
  (Thread/sleep duration))

(defmacro with-retry
  [{:keys [max-retries base-delay-ms cap-ms]
    :or {max-retries 256
         base-delay-ms 50
         cap-ms 10000}} & body]
  `(do
     (when (<= ~max-retries 0)
       (anomaly! ":max-retries must be >= 1" ::anomalies/incorrect))

     (when (neg? ~base-delay-ms)
       (anomaly! ":base-delay-ms must be >= 0" ::anomalies/incorrect))

     (when (neg? ~cap-ms)
       (anomaly! ":cap-ms must be >= 0" ::anomalies/incorrect))

     (loop [attempt# 0]
       (let [ret# (try
                    (do ~@body)
                    (catch Exception ex#
                      ex#))]
         (if (instance? Exception ret#)
           (cond
             (not (anomaly/retryable? ret#))
             (do
               (log :severe :non-retryable-error (Throwable->map ret#))
               (throw ret#))

             (= attempt# ~max-retries)
             (do
               (log :severe :max-retries (Throwable->map ret#))
               (throw (ex-info (ex-message ret#) {:attempt attempt# :ex ret#})))

             :else
             (let [attempt# (inc attempt#)
                   delay-ms# (calculate-delay-ms ~base-delay-ms ~cap-ms attempt#)]
               (log :info :retry {:attempt attempt# :delay-ms (.toMillis delay-ms#)})
               (sleep delay-ms#)
               (recur attempt#)))
           (do
             (log :info :retry-success {:attempt attempt#})
             ret#))))))
