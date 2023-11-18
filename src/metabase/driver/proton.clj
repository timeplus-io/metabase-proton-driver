(ns metabase.driver.proton
  "Driver for Timeplus Proton databases"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [metabase.driver :as driver]
            [metabase.driver.proton-introspection]
            [metabase.driver.proton-qp]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.driver.sql :as driver.sql]
            [metabase.driver.sql-jdbc [common :as sql-jdbc.common]
             [connection :as sql-jdbc.conn]
             [sync :as sql-jdbc.sync]]
            [metabase [config :as config]]))

(set! *warn-on-reflection* true)

(driver/register! :proton :parent :sql-jdbc)

(defmethod driver/display-name :proton [_] "Timeplus Proton")
(def ^:private product-name "metabase/1.2.3")

(doseq [[feature supported?] {:standard-deviation-aggregations true
                              :foreign-keys                    (not config/is-test?)
                              :set-timezone                    false
                              :convert-timezone                false
                              :test/jvm-timezone-setting       false
                              :connection-impersonation        false
                              :schemas                         true}]

  (defmethod driver/database-supports? [:proton feature] [_driver _feature _db] supported?))

(def ^:private default-connection-details
  {:user "default" :password "" :dbname "default" :host "localhost" :port "3218"})

(defmethod sql-jdbc.conn/connection-details->spec :proton
  [_ details]
  ;; ensure defaults merge on top of nils
  (let [details (reduce-kv (fn [m k v] (assoc m k (or v (k default-connection-details))))
                           default-connection-details
                           details)
        {:keys [user password dbname host port ssl use-no-proxy]} details]
    (->
     {:classname "com.timeplus.proton.jdbc.ProtonDriver"
      :subprotocol "proton"
      :subname (str "//" host ":" port "/" dbname)
      :password (or password "")
      :user user
      :ssl (boolean ssl)
      :use_no_proxy (boolean use-no-proxy)
      :use_server_time_zone_for_dates true
      :product_name product-name}
     (sql-jdbc.common/handle-additional-options details :separator-style :url))))

(defmethod sql-jdbc.sync/db-default-timezone :proton
  [_ spec]
  (let [sql (str "SELECT timezone() AS tz")
        [{:keys [tz]}] (jdbc/query spec sql)]
    tz))

(defmethod driver/db-start-of-week :proton [_] :monday)

(defmethod ddl.i/format-name :proton [_ table-or-field-name]
  (str/replace table-or-field-name #"-" "_"))

;;; ------------------------------------------ User Impersonation ------------------------------------------

(defmethod driver.sql/set-role-statement :proton
  [_ role]
  (format "SET ROLE %s;" role))

(defmethod driver.sql/default-database-role :proton
  [_ _]
  "NONE")