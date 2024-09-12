(ns metabase.driver.proton
  "Driver for Timeplus"
  #_{:clj-kondo/ignore [:unsorted-required-namespaces]}
  (:require [clojure.core.memoize :as memoize]
            [clojure.string :as str]
            [honey.sql :as sql]
            [metabase.config :as config]
            [metabase.driver :as driver]
            [metabase.driver.proton-introspection]
            [metabase.driver.proton-qp]
            [metabase.driver.proton-version :as proton-version]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.driver.sql :as driver.sql]
            [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql.util :as sql.u]
            [metabase.lib.metadata :as lib.metadata]
            [metabase.query-processor.store :as qp.store]
            [metabase.upload :as upload]
            [metabase.util :as u]
            [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(driver/register! :proton :parent #{:sql-jdbc})

(defmethod driver/display-name :proton [_] "Timeplus")
(def ^:private product-name "metabase/1.50.3")

(defmethod driver/prettify-native-form :proton
  [_ native-form]
  (sql.u/format-sql-and-fix-params :mysql native-form))

(doseq [[feature supported?] {:standard-deviation-aggregations true
                              :foreign-keys                    (not config/is-test?)
                              :now                             true
                              :set-timezone                    true
                              :convert-timezone                true
                              :test/jvm-timezone-setting       false
                              :schemas                         true
                              :datetime-diff                   true
                              :upload-with-auto-pk             false
                              :window-functions/offset         false}]
  (defmethod driver/database-supports? [:proton feature] [_driver _feature _db] supported?))

(def ^:private default-connection-details
  {:user "default" :password "" :dbname "default" :host "localhost" :port "8123"})

(defn- connection-details->spec* [details]
  (log/trace "connection-details->spec* called with details:" details)
  (let [;; ensure defaults merge on top of nils
        details (reduce-kv (fn [m k v] (assoc m k (or v (k default-connection-details))))
                           default-connection-details
                           details)
        {:keys [user password dbname host port ssl use-no-proxy]} details
        ;; if multiple databases were specified for the connection,
        ;; use only the first dbname as the "main" one
        dbname (first (str/split (str/trim dbname) #" "))]
    (->
     {:classname "com.timeplus.proton.jdbc.ProtonDriver"
      :subprotocol "proton"
      :subname (str "//" host ":" port "/" dbname)
      :password (or password "")
      :user user
      :ssl (boolean ssl)
      :use_no_proxy (boolean use-no-proxy)
      :use_server_time_zone_for_dates true
      :product_name product-name
      :databaseTerm "schema"
      :remember_last_set_roles false
      :http_connection_provider "HTTP_URL_CONNECTION"}
     (sql-jdbc.common/handle-additional-options details :separator-style :url))))

(defmethod sql-jdbc.execute/do-with-connection-with-options :proton
  [driver db-or-id-or-spec {:keys [^String session-timezone write?] :as options} f]
  (sql-jdbc.execute/do-with-resolved-connection
   driver
   db-or-id-or-spec
   options
   (fn [^java.sql.Connection conn]
     (when-not (sql-jdbc.execute/recursive-connection?)
       (when session-timezone
         (.setClientInfo conn com.timeplus.proton.jdbc.ProtonConnection/PROP_CUSTOM_HTTP_PARAMS
                         (format "session_timezone=%s" session-timezone)))

       (sql-jdbc.execute/set-best-transaction-level! driver conn)
       (sql-jdbc.execute/set-time-zone-if-supported! driver conn session-timezone)
       (when-let [db (cond
                       ;; id?
                       (integer? db-or-id-or-spec)
                       (qp.store/with-metadata-provider db-or-id-or-spec
                         (lib.metadata/database (qp.store/metadata-provider)))
                       ;; db?
                       (u/id db-or-id-or-spec)     db-or-id-or-spec
                       ;; otherwise it's a spec and we can't get the db
                       :else nil)]
         (sql-jdbc.execute/set-role-if-supported! driver conn db))
       (when-not write?
         (try
           (log/trace (pr-str '(.setAutoCommit conn true)))
           (.setAutoCommit conn true)
           (catch Throwable e
             (log/debug e "Error enabling connection autoCommit"))))
       (try
         (log/trace (pr-str '(.setHoldability conn java.sql.ResultSet/CLOSE_CURSORS_AT_COMMIT)))
         (.setHoldability conn java.sql.ResultSet/CLOSE_CURSORS_AT_COMMIT)
         (catch Throwable e
           (log/debug e "Error setting default holdability for connection"))))
     (f conn))))

(def ^:private ^{:arglists '([db-details])} cloud?
  "Returns true if the `db-details` are for a ClickHouse Cloud instance(Not Appliable), and false otherwise. If it fails to connect
   to the database, it throws a java.sql.SQLException."
  (memoize/ttl
   (fn [db-details]
     (let [spec (connection-details->spec* db-details)]
       (sql-jdbc.execute/do-with-connection-with-options
        :proton spec nil
        (fn [^java.sql.Connection conn]
          (with-open [stmt (.prepareStatement conn "SELECT value='1' FROM system.settings WHERE name='cloud_mode'")
                      rset (.executeQuery stmt)]
            (if (.next rset) (.getBoolean rset 1) false))))))
   ;; cache the results for 48 hours; TTL is here only to eventually clear out old entries
   :ttl/threshold (* 48 60 60 1000)))

(defmethod sql-jdbc.conn/connection-details->spec :proton
  [_ details]
  (cond-> (connection-details->spec* details)
    (try (cloud? details)
         (catch java.sql.SQLException _e
           false))
    ;; select_sequential_consistency guarantees that we can query data from any replica in CH Cloud
    ;; immediately after it is written
    (assoc :select_sequential_consistency true)))

(defmethod driver/database-supports? [:proton :uploads] [_driver _feature db]
  (if (:details db)
    (try (cloud? (:details db))
         (catch java.sql.SQLException _e
           false))
    false))

(defmethod driver/can-connect? :proton
  [driver details]
  (log/trace "can-connect")
  (if config/is-test?
    (try
      ;; Default SELECT 1 is not enough for Metabase test suite,
      ;; as it works slightly differently than expected there
      (let [spec  (sql-jdbc.conn/connection-details->spec driver details)
            db    (or (:dbname details) (:db details) "default")]
        (sql-jdbc.conn/do-with-connection-spec-for-testing-connection
         driver spec nil
         (fn [^java.sql.Connection conn]
           (let [stmt (.prepareStatement conn "SELECT count(*) > 0 FROM system.databases WHERE name = ?")
                 _    (.setString stmt 1 db)
                 rset (.executeQuery stmt)]
             (when (.next rset)
               (.getBoolean rset 1))))))
      (catch Throwable e
        (log/error e "An exception during Timeplus connectivity check")
        false))
    ;; During normal usage, fall back to the default implementation
    (sql-jdbc.conn/can-connect? driver details)))

(defmethod driver/db-default-timezone :proton
  [driver database]
  (sql-jdbc.execute/do-with-connection-with-options
   driver database nil
   (fn [^java.sql.Connection conn]
     (with-open [stmt (.prepareStatement conn "SELECT timezone() AS tz")
                 rset (.executeQuery stmt)]
       (when (.next rset)
         (.getString rset 1))))))

(defmethod driver/db-start-of-week :proton [_] :monday)

(defmethod ddl.i/format-name :proton
  [_ table-or-field-name]
  (str/replace table-or-field-name #"-" "_"))

;;; ------------------------------------------ Connection Impersonation ------------------------------------------

; (defmethod driver/upload-type->database-type :proton
;   [_driver upload-type]
;   (case upload-type
;     ::upload/varchar-255              "nullable(string)"
;     ::upload/text                     "nullable(string)"
;     ::upload/int                      "nullable(int64)"
;     ::upload/float                    "nullable(float64)"
;     ::upload/boolean                  "nullable(boolean)"
;     ::upload/date                     "nullable(date32)"
;     ::upload/datetime                 "nullable(datetime64(3))"
;     ::upload/offset-datetime          "nullable(datetime64(3))"))

(defmethod driver/table-name-length-limit :proton
  [_driver]
  ;; FIXME: This is a lie because you're really limited by a filesystems' limits, because Proton uses
  ;; filenames as table/column names. But its an approximation
  206)

(defn- quote-name [s]
  (let [parts (str/split (name s) #"\.")]
    (str/join "." (map #(str "`" % "`") parts))))

;;; ------------------------------------------ User Impersonation ------------------------------------------

(defmethod driver/database-supports? [:proton :connection-impersonation]
  [_driver _feature db]
  (if db
    (try (proton-version/is-at-least? 1 5 db)
         (catch Throwable _e
           false))
    false))

(defmethod driver.sql/set-role-statement :proton
  [_ role]
  (format "SET ROLE %s;" role))

(defmethod driver.sql/default-database-role :proton
  [_ _]
  "NONE")
