(ns ^:no-doc rad.impl.anomaly
  (:require [cognitect.anomalies :as-alias anomalies]))

(defmacro anomaly!
  "Given an exception message, an anomaly category, and optional ex-data, throw
  an exception indicating the given anomaly."
  ([msg category]
   `(anomaly! ~msg ~category nil))
  ([msg category data]
   `(throw (ex-info ~msg (merge ~data {::anomalies/category ~category})))))

(comment
  (anomaly! "Boom!" ::anomalies/fault)
  (anomaly! "Boom!" ::anomalies/fault {:extra :data})
  ,,,)

(defn retryable?
  "Given an exception, return true if the exception indicates that the operation
  that threw the exception is retryable."
  [ex]
  (boolean
    (#{::anomalies/unavailable
       ::anomalies/interrupted
       ::anomalies/busy}
     (-> ex ex-data ::anomalies/category))))
