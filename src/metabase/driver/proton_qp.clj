(ns metabase.driver.proton-qp
  "Proton driver: QueryProcessor-related definition"
  #_{:clj-kondo/ignore [:unsorted-required-namespaces]}
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [java-time.api :as t]
            [metabase.driver.proton-version :as proton-version]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql.query-processor :as sql.qp :refer [add-interval-honeysql-form]]
            [metabase.driver.sql.util :as sql.u]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.legacy-mbql.util :as mbql.u]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.util :as u]
            [metabase.util.date-2 :as u.date]
            [metabase.util.honey-sql-2 :as h2x]
            [metabase.util.log :as log])
  (:import [com.timeplus.proton.client.data ProtonArrayValue]
           [java.sql ResultSet ResultSetMetaData Types]
           [java.time
            LocalDate
            LocalDateTime
            LocalTime
            OffsetDateTime
            OffsetTime
            ZonedDateTime]
           java.util.Arrays))

;; (set! *warn-on-reflection* true) ;; isn't enabled because of Arrays/toString call

(defmethod sql.qp/quote-style :proton [_] :mysql)

;; without try, there might be test failures when QP is not yet initialized
;; e.g., when a test is preparing the dataset
(defn- get-report-timezone-id-safely
  []
  (try
    (qp.timezone/report-timezone-id-if-supported)
    (catch Throwable _e nil)))

;; datetime('europe/amsterdam') -> europe/amsterdam
(defn- extract-datetime-timezone
  [db-type]
  (when (and db-type (string? db-type))
    (cond
      ;; e.g. DateTime64(3, 'Europe/Amsterdam')
      (str/starts-with? db-type "datetime64")
      (if (> (count db-type) 17) (subs db-type 15 (- (count db-type) 2)) nil)
      ;; e.g. DateTime('Europe/Amsterdam')
      (str/starts-with? db-type "datetime")
      (if (> (count db-type) 12) (subs db-type 10 (- (count db-type) 2)) nil)
      ;; _
      :else nil)))

(defn- remove-low-cardinality-and-nullable
  [db-type]
  (when (and db-type (string? db-type))
    (let [without-low-car  (if (str/starts-with? db-type "lowcardinality(")
                             (subs db-type 15 (- (count db-type) 1))
                             db-type)
          without-nullable (if (str/starts-with? without-low-car "nullable(")
                             (subs without-low-car 9 (- (count without-low-car) 1))
                             without-low-car)]
      without-nullable)))

(defn- in-report-timezone
  [expr]
  (let [report-timezone (get-report-timezone-id-safely)
        lower           (u/lower-case-en (h2x/database-type expr))
        db-type         (remove-low-cardinality-and-nullable lower)]
    (if (and report-timezone (string? db-type) (str/starts-with? db-type "datetime"))
      (let [timezone (extract-datetime-timezone db-type)]
        (if (not (= timezone (u/lower-case-en report-timezone)))
          [:'to_time_zone expr (h2x/literal report-timezone)]
          expr))
      expr)))

(defmethod sql.qp/date [:proton :default]
  [_ _ expr]
  expr)

;;; ------------------------------------------------------------------------------------
;;; Extract functions
;;; ------------------------------------------------------------------------------------

(defn- date-extract
  [ch-fn expr db-type]
  (-> [ch-fn (in-report-timezone expr)]
      (h2x/with-database-type-info db-type)))

(defmethod sql.qp/date [:proton :day-of-week]
  [_ _ expr]
  ;; a tick in the function name prevents HSQL2 to make the function call UPPERCASE
  ;; https://cljdoc.org/d/com.github.seancorfield/honeysql/2.4.1011/doc/getting-started/other-databases#clickhouse
  (sql.qp/adjust-day-of-week
   :proton (date-extract :'to_day_of_week expr "uint8")))

(defmethod sql.qp/date [:proton :month-of-year]
  [_ _ expr]
  (date-extract :'to_month expr "uint8"))

(defmethod sql.qp/date [:proton :minute-of-hour]
  [_ _ expr]
  (date-extract :'to_minute expr "uint8"))

(defmethod sql.qp/date [:proton :hour-of-day]
  [_ _ expr]
  (date-extract :'to_hour expr "uint8"))

(defmethod sql.qp/date [:proton :day-of-month]
  [_ _ expr]
  (date-extract :'to_day_of_month expr "uint8"))

(defmethod sql.qp/date [:proton :day-of-year]
  [_ _ expr]
  (date-extract :'to_day_of_year expr "uint16"))

(defmethod sql.qp/date [:proton :week-of-year-iso]
  [_ _ expr]
  (date-extract :'to_iso_week expr "uint8"))

(defmethod sql.qp/date [:proton :quarter-of-year]
  [_ _ expr]
  (date-extract :'to_quarter expr "uint8"))

(defmethod sql.qp/date [:proton :year-of-era]
  [_ _ expr]
  (date-extract :'to_year expr "uint16"))

;;; ------------------------------------------------------------------------------------
;;; Truncate functions
;;; ------------------------------------------------------------------------------------

(defn- date-trunc
  [ch-fn expr]
  (-> [ch-fn (in-report-timezone expr)]
      (h2x/with-database-type-info (h2x/database-type expr))))

(defn- to-start-of-week
  [expr]
  (date-trunc :'to_monday expr))

(defmethod sql.qp/date [:proton :minute]
  [_ _ expr]
  (date-trunc :'to_start_of_minute expr))

(defmethod sql.qp/date [:proton :hour]
  [_ _ expr]
  (date-trunc :'to_start_of_hour expr))

(defmethod sql.qp/date [:proton :day]
  [_ _ expr]
  (date-trunc :'to_start_of_day expr))

(defmethod sql.qp/date [:proton :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week driver to-start-of-week expr))

(defmethod sql.qp/date [:proton :month]
  [_ _ expr]
  (date-trunc :'to_start_of_month expr))

(defmethod sql.qp/date [:proton :quarter]
  [_ _ expr]
  (date-trunc :'to_start_of_quarter expr))

(defmethod sql.qp/date [:proton :year]
  [_ _ expr]
  (date-trunc :'to_start_of_year expr))

;;; ------------------------------------------------------------------------------------
;;; Unix timestamps functions
;;; ------------------------------------------------------------------------------------

(defmethod sql.qp/unix-timestamp->honeysql [:proton :seconds]
  [_ _ expr]
  (h2x/->datetime expr))

(defmethod sql.qp/unix-timestamp->honeysql [:proton :milliseconds]
  [_ _ expr]
  (let [report-timezone (get-report-timezone-id-safely)
        inner-expr      (h2x// expr 1000)]
    (if report-timezone
      [:'to_date_time64 inner-expr 3 report-timezone]
      [:'to_date_time64 inner-expr 3])))

(defmethod sql.qp/unix-timestamp->honeysql [:proton :microseconds]
  [_ _ expr]
  (let [report-timezone (get-report-timezone-id-safely)
        inner-expr      [:'to_int64 (h2x// expr 1000)]]
    (if report-timezone
      [:'from_unix_timestamp64_milli inner-expr report-timezone]
      [:'from_unix_timestamp64_milli inner-expr])))

;;; ------------------------------------------------------------------------------------
;;; HoneySQL forms
;;; ------------------------------------------------------------------------------------

(defmethod sql.qp/->honeysql [:proton :convert-timezone]
  [driver [_ arg target-timezone source-timezone]]
  (let [expr          (sql.qp/->honeysql driver (cond-> arg (string? arg) u.date/parse))
        with-tz-info? (h2x/is-of-type? expr #"(?:nullable\(|lowcardinality\()?(datetime64\(\d, {0,1}'.*|datetime\(.*)")
        _             (sql.u/validate-convert-timezone-args with-tz-info? target-timezone source-timezone)]
    (if (not with-tz-info?)
      [:'plus
       expr
       [:'toIntervalSecond
        [:'minus
         [:'time_zone_offset [:'to_time_zone expr target-timezone]]
         [:'time_zone_offset [:'to_time_zone expr source-timezone]]]]]
      [:'to_time_zone expr target-timezone])))

(defmethod sql.qp/current-datetime-honeysql-form :proton
  [_]
  (let [report-timezone (get-report-timezone-id-safely)
        [expr db-type]  (if report-timezone
                          [[:'now64 [:raw 9] (h2x/literal report-timezone)] (format "DateTime64(9, '%s')" report-timezone)]
                          [[:'now64 [:raw 9]] "DateTime64(9)"])]
    (h2x/with-database-type-info expr db-type)))

(defn- date-time-parse-fn
  [nano]
  (if (zero? nano) :'parse_date_time_best_effort :'parse_date_time64_best_effort))

(defmethod sql.qp/->honeysql [:proton LocalDateTime]
  [_ ^java.time.LocalDateTime t]
  (let [formatted (t/format "yyyy-MM-dd HH:mm:ss.SSS" t)]
    (if (zero? (.getNano t))
      [:'parse_date_time_best_effort   formatted]
      [:'parse_date_time64_best_effort formatted 3])))

(defmethod sql.qp/->honeysql [:proton ZonedDateTime]
  [_ ^java.time.ZonedDateTime t]
  (let [formatted (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)
        fn        (date-time-parse-fn (.getNano t))]
    [fn formatted]))

(defmethod sql.qp/->honeysql [:proton OffsetDateTime]
  [_ ^java.time.OffsetDateTime t]
  ;; copy-paste due to reflection warnings
  (let [formatted (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)
        fn        (date-time-parse-fn (.getNano t))]
    [fn formatted]))

(defmethod sql.qp/->honeysql [:proton LocalDate]
  [_ ^java.time.LocalDate t]
  [:'parse_date_time_best_effort t])

(defn- local-date-time
  [^java.time.LocalTime t]
  (t/local-date-time (t/local-date 1970 1 1) t))

(defmethod sql.qp/->honeysql [:proton LocalTime]
  [driver ^java.time.LocalTime t]
  (sql.qp/->honeysql driver (local-date-time t)))

(defmethod sql.qp/->honeysql [:proton OffsetTime]
  [driver ^java.time.OffsetTime t]
  (sql.qp/->honeysql driver (t/offset-date-time
                             (local-date-time (.toLocalTime t))
                             (.getOffset t))))

(defn- args->float64
  [args]
  (map (fn [arg] [:'to_float64 (sql.qp/->honeysql :proton arg)]) args))

(defn- interval? [expr]
  (mbql.u/is-clause? :interval expr))

(defmethod sql.qp/->honeysql [:proton :+]
  [driver [_ & args]]
  (if (some interval? args)
    (if-let [[field intervals] (u/pick-first (complement interval?) args)]
      (reduce (fn [hsql-form [_ amount unit]]
                (add-interval-honeysql-form driver hsql-form amount unit))
              (sql.qp/->honeysql driver field)
              intervals)
      (throw (ex-info "Summing intervals is not supported" {:args args})))
    (into [:+] (args->float64 args))))

(defmethod sql.qp/->honeysql [:proton :log]
  [driver [_ field]]
  [:'log10 (sql.qp/->honeysql driver field)])

(defn- format-expr
  [expr]
  (first (sql/format-expr (sql.qp/->honeysql :proton expr) {:nested true})))

(defmethod sql.qp/->honeysql [:proton :percentile]
  [_ [_ field p]]
  [:raw (format "quantile(%s)(%s)" (format-expr p) (format-expr field))])

(defmethod sql.qp/->honeysql [:proton :regex-match-first]
  [driver [_ arg pattern]]
  [:'extract (sql.qp/->honeysql driver arg) pattern])

(defmethod sql.qp/->honeysql [:proton :stddev]
  [driver [_ field]]
  [:'stddev_pop (sql.qp/->honeysql driver field)])

(defmethod sql.qp/->honeysql [:proton :median]
  [driver [_ field]]
  [:'median (sql.qp/->honeysql driver field)])

;; Substring does not work for Enums, so we need to cast to String
(defmethod sql.qp/->honeysql [:proton :substring]
  [driver [_ arg start length]]
  (let [str [:'to_string (sql.qp/->honeysql driver arg)]]
    (if length
      [:'substring str
       (sql.qp/->honeysql driver start)
       (sql.qp/->honeysql driver length)]
      [:'substring str
       (sql.qp/->honeysql driver start)])))

(defmethod sql.qp/->honeysql [:proton :var]
  [driver [_ field]]
  [:'var_pop (sql.qp/->honeysql driver field)])

(defmethod sql.qp/->float :proton
  [_ value]
  [:'to_float64 value])

(defmethod sql.qp/->honeysql [:proton :value]
  [driver value]
  (let [[_ value {base-type :base_type}] value]
    (when (some? value)
      (condp #(isa? %2 %1) base-type
        :type/IPAddress [:'to_ipv4 value]
        (sql.qp/->honeysql driver value)))))

(defmethod sql.qp/->honeysql [:proton :=]
  [driver [op field value]]
  (let [[qual valuevalue fieldinfo] value
        hsql-field (sql.qp/->honeysql driver field)
        hsql-value (sql.qp/->honeysql driver value)]
    (if (and (isa? qual :value)
             (isa? (:base_type fieldinfo) :type/Text)
             (nil? valuevalue))
      [:or
       [:= hsql-field hsql-value]
       [:= [:'empty hsql-field] 1]]
      ((get-method sql.qp/->honeysql [:sql :=]) driver [op field value]))))

(defmethod sql.qp/->honeysql [:proton :!=]
  [driver [op field value]]
  (let [[qual valuevalue fieldinfo] value
        hsql-field (sql.qp/->honeysql driver field)
        hsql-value (sql.qp/->honeysql driver value)]
    (if (and (isa? qual :value)
             (isa? (:base_type fieldinfo) :type/Text)
             (nil? valuevalue))
      [:and
       [:!= hsql-field hsql-value]
       [:= [:'not_empty hsql-field] 1]]
      ((get-method sql.qp/->honeysql [:sql :!=]) driver [op field value]))))

;; I do not know why the tests expect nil counts for empty results
;; but that's how it is :-)
;;
;; It would even be better if we could use countIf and sumIf directly
;;
;; metabase.query-processor-test.count-where-test
;; metabase.query-processor-test.share-test
(defmethod sql.qp/->honeysql [:proton :count-where]
  [driver [_ pred]]
  [:case
   [:> [:'count] 0]
   [:sum [:case (sql.qp/->honeysql driver pred) 1 :else 0]]
   :else nil])

(defmethod sql.qp/->honeysql [:proton :sum-where]
  [driver [_ field pred]]
  [:sum [:case (sql.qp/->honeysql driver pred) (sql.qp/->honeysql driver field)
         :else 0]])

(defmethod sql.qp/add-interval-honeysql-form :proton
  [_ dt amount unit]
  (h2x/+ dt [:raw (format "INTERVAL %d %s" (int amount) (name unit))]))

(defn- proton-string-fn
  [fn-name field value options]
  (let [hsql-field (sql.qp/->honeysql :proton field)
        hsql-value (sql.qp/->honeysql :proton value)]
    (if (get options :case-sensitive true)
      [fn-name hsql-field hsql-value]
      [fn-name [:'lower_utf8 hsql-field] [:'lower_utf8 hsql-value]])))

(defmethod sql.qp/->honeysql [:proton :starts-with]
  [_ [_ field value options]]
  (let [starts-with (proton-version/with-min 1 5
                      (constantly :'starts_with_utf8)
                      (constantly :'starts_with))]
    (proton-string-fn starts-with field value options)))

(defmethod sql.qp/->honeysql [:proton :ends-with]
  [_ [_ field value options]]
  (let [ends-with (proton-version/with-min 1 5
                    (constantly :'ends_with_utf8)
                    (constantly :'ends_with))]
    (proton-string-fn ends-with field value options)))

(defmethod sql.qp/->honeysql [:proton :contains]
  [_ [_ field value options]]
  (let [hsql-field (sql.qp/->honeysql :proton field)
        hsql-value (sql.qp/->honeysql :proton value)
        position-fn (if (get options :case-sensitive true)
                      :'position_utf8
                      :'position_case_insensitive_utf8)]
    [:> [position-fn hsql-field hsql-value] 0]))

(defmethod sql.qp/->honeysql [:proton :datetime-diff]
  [driver [_ x y unit]]
  (let [x (sql.qp/->honeysql driver x)
        y (sql.qp/->honeysql driver y)]
    (case unit
      ;; Week: Metabase tests expect a bit different result from what `age` provides
      (:week)
      [:'int_div [:'date_diff (h2x/literal :day) (date-trunc :'to_start_of_day x) (date-trunc :'to_start_of_day y)] [:raw 7]]
      ;; -------------------------
      (:year :month :quarter :day)
      [:'age (h2x/literal unit) (date-trunc :'to_start_of_day x) (date-trunc :'to_start_of_day y)]
      ;; -------------------------
      (:hour :minute :second)
      [:'age (h2x/literal unit) (in-report-timezone x) (in-report-timezone y)])))

;; We do not have Time data types, so we cheat a little bit
(defmethod sql.qp/cast-temporal-string [:proton :Coercion/ISO8601->Time]
  [_driver _special_type expr]
  [:'parse_date_time_best_effort [:'concat "1970-01-01T" expr]])

(defmethod sql.qp/cast-temporal-byte [:proton :Coercion/ISO8601->Time]
  [_driver _special_type expr]
  expr)

;;; ------------------------------------------------------------------------------------
;;; JDBC-related functions
;;; ------------------------------------------------------------------------------------

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/TINYINT]
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (.getByte rs i)))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/SMALLINT]
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (.getShort rs i)))

;; This is for tests only - some of them expect nil values
;; getInt/getLong return 0 in case of a NULL value in the result set
;; the only way to check if it was actually NULL - call ResultSet.wasNull afterwards
(defn- with-null-check
  [^ResultSet rs value]
  (if (.wasNull rs) nil value))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/BIGINT]
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (with-null-check rs (.getLong rs i))))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/INTEGER]
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (with-null-check rs (.getInt rs i))))

(defn- offset-date-time->maybe-offset-time
  [^OffsetDateTime r]
  (cond (nil? r) nil
        (= (.toLocalDate r) (t/local-date 1970 1 1)) (.toOffsetTime r)
        :else r))

(defn- local-date-time->maybe-local-time
  [^LocalDateTime r]
  (cond (nil? r) nil
        (= (.toLocalDate r) (t/local-date 1970 1 1)) (.toLocalTime r)
        :else r))

(defn- get-date-or-time-type
  [tz-check-fn ^ResultSet rs ^Integer i]
  (if (tz-check-fn)
    (offset-date-time->maybe-offset-time (.getObject rs i OffsetDateTime))
    (local-date-time->maybe-local-time (.getObject rs i LocalDateTime))))

(defn- read-timestamp-column
  [^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (let [db-type (remove-low-cardinality-and-nullable (u/lower-case-en (.getColumnTypeName rsmeta i)))]
    (cond
      ;; DateTime64 with tz info
      (str/starts-with? db-type "datetime64")
      (get-date-or-time-type #(> (count db-type) 13) rs i)
      ;; DateTime with tz info
      (str/starts-with? db-type "datetime")
      (get-date-or-time-type #(> (count db-type) 8) rs i)
      ;; _
      :else (.getObject rs i LocalDateTime))))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/TIMESTAMP]
  [_ ^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (fn []
    (read-timestamp-column rs rsmeta i)))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/TIMESTAMP_WITH_TIMEZONE]
  [_ ^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (fn []
    (read-timestamp-column rs rsmeta i)))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/TIME]
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (.getObject rs i OffsetTime)))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/NUMERIC]
  [_ ^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (fn []
    ; count is NUMERIC cause UInt64 is too large for the canonical SQL BIGINT,
    ; and defaults to BigDecimal, but we want it to be coerced to java Long
    ; cause it still fits and the tests are expecting that
    (if (= (.getColumnLabel rsmeta i) "count")
      (.getLong rs i)
      (.getBigDecimal rs i))))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/ARRAY]
  [_ ^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (fn []
    (when-let [arr (.getArray rs i)]
      (let [col-type-name (.getColumnTypeName rsmeta i)
            inner         (.getArray arr)]
        (cond
          (= "Bool" col-type-name)
          (str "[" (str/join ", " (map #(if (= 1 %) "true" "false") inner)) "]")
          (= "Nullable(Bool)" col-type-name)
          (str "[" (str/join ", " (map #(cond (= 1 %) "true" (= 0 %) "false" :else "null") inner)) "]")
          ;; All other primitives
          (.isPrimitive (.getComponentType (.getClass inner)))
          (Arrays/toString inner)
          ;; Complex types
          :else
          (.asString (ProtonArrayValue/of inner)))))))

(defn- ip-column->string
  [^ResultSet rs ^Integer i]
  (when-let [inet-address (.getObject rs i java.net.InetAddress)]
    (.getHostAddress inet-address)))

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/VARCHAR]
  [_ ^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (fn []
    (cond
      (str/starts-with? (.getColumnTypeName rsmeta i) "IPv") (ip-column->string rs i)
      :else (.getString rs i))))

(defmethod unprepare/unprepare-value [:proton LocalDate]
  [_ t]
  (format "'%s'" (t/format "yyyy-MM-dd" t)))

(defmethod unprepare/unprepare-value [:proton LocalTime]
  [_ t]
  (format "'%s'" (t/format "HH:mm:ss.SSS" t)))

(defmethod unprepare/unprepare-value [:proton OffsetTime]
  [_ t]
  (format "'%s'" (t/format "HH:mm:ss.SSSZZZZZ" t)))

(defmethod unprepare/unprepare-value [:proton LocalDateTime]
  [_ t]
  (format "'%s'" (t/format "yyyy-MM-dd HH:mm:ss.SSS" t)))

(defmethod unprepare/unprepare-value [:proton OffsetDateTime]
  [_ ^OffsetDateTime t]
  (format "%s('%s')"
          (if (zero? (.getNano t)) "parse_date_time_best_effort" "parse_date_time64_best_effort")
          (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)))

(defmethod unprepare/unprepare-value [:proton ZonedDateTime]
  [_ t]
  (format "'%s'" (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)))