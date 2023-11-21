(ns metabase.driver.proton-qp
  "Proton driver: QueryProcessor-related definition"
  #_{:clj-kondo/ignore [:unsorted-required-namespaces]}
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [java-time.api :as t]
            [metabase [util :as u]]
            [metabase.driver.sql-jdbc [execute :as sql-jdbc.execute]]
            [metabase.driver.sql.query-processor :as sql.qp :refer [add-interval-honeysql-form]]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.mbql.schema :as mbql.s]
            [metabase.mbql.util :as mbql.u]
            [metabase.util.date-2 :as u.date]
            [metabase.util.honey-sql-2 :as h2x]
            [schema.core :as s])
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

(defmethod sql.qp/quote-style       :proton [_] :mysql)
(defmethod sql.qp/honey-sql-version :proton [_] 2)

(defmethod sql.qp/date [:proton :day-of-week]
  [_ _ expr]
  ;; a tick in the function name prevents HSQL2 to make the function call UPPERCASE
  ;; https://cljdoc.org/d/com.github.seancorfield/honeysql/2.4.1011/doc/getting-started/other-databases#clickhouse
  (sql.qp/adjust-day-of-week :proton [:'day_of_week expr]))

(defmethod sql.qp/date [:proton :default]
  [_ _ expr]
  expr)

(defmethod sql.qp/date [:proton :minute]
  [_ _ expr]
  [:'to_start_of_minute expr])

(defmethod sql.qp/date [:proton :minute-of-hour]
  [_ _ expr]
  [:'to_minute expr])

(defmethod sql.qp/date [:proton :hour]
  [_ _ expr]
  [:'to_start_of_hour expr])

(defmethod sql.qp/date [:proton :hour-of-day]
  [_ _ expr]
  [:'to_hour expr])

(defmethod sql.qp/date [:proton :day-of-month]
  [_ _ expr]
  [:'to_day_of_month expr])

(defn- to-start-of-week
  [expr]
  [:'to_monday expr])

(defn- to-start-of-year
  [expr]
  [:'to_start_of_year expr])

(defn- to-relative-day-num
  [expr]
  [:'to_relative_day_num expr])

(defmethod sql.qp/date [:proton :day-of-year]
  [_ _ expr]
  (h2x/+
   (h2x/- (to-relative-day-num expr)
          (to-relative-day-num (to-start-of-year expr)))
   1))

(defmethod sql.qp/date [:proton :week-of-year-iso]
  [_ _ expr]
  [:'to_iso_week expr])

(defmethod sql.qp/date [:proton :month]
  [_ _ expr]
  [:'to_start_of_month expr])

(defmethod sql.qp/date [:proton :month-of-year]
  [_ _ expr]
  [:'to_month expr])

(defmethod sql.qp/date [:proton :quarter-of-year]
  [_ _ expr]
  [:'to_quarter expr])

(defmethod sql.qp/date [:proton :year]
  [_ _ expr]
  [:'to_start_of_year expr])

(defmethod sql.qp/date [:proton :day]
  [_ _ expr]
  (h2x/->date expr))

(defmethod sql.qp/date [:proton :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week driver to-start-of-week expr))

(defmethod sql.qp/date [:proton :quarter]
  [_ _ expr]
  [:'to_start_of_quarter expr])

(defmethod sql.qp/unix-timestamp->honeysql [:proton :seconds]
  [_ _ expr]
  (h2x/->datetime expr))

(defmethod sql.qp/unix-timestamp->honeysql [:proton :milliseconds]
  [_ _ expr]
  [:'to_datetime64 (h2x// expr 1000) 3])

(defn- date-time-parse-fn
  [nano]
  (if (zero? nano) :'parse_datetime_best_effort :'parse_datetime64_best_effort))

(defmethod sql.qp/->honeysql [:proton LocalDateTime]
  [_ ^java.time.LocalDateTime t]
  (let [formatted (t/format "yyyy-MM-dd HH:mm:ss.SSS" t)
        fn (date-time-parse-fn (.getNano t))]
    [fn formatted]))

(defmethod sql.qp/->honeysql [:proton ZonedDateTime]
  [_ ^java.time.ZonedDateTime t]
  (let [formatted (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)
        fn (date-time-parse-fn (.getNano t))]
    [fn formatted]))

(defmethod sql.qp/->honeysql [:proton OffsetDateTime]
  [_ ^java.time.OffsetDateTime t]
  ;; copy-paste due to reflection warnings
  (let [formatted (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)
        fn (date-time-parse-fn (.getNano t))]
    [fn formatted]))

(defmethod sql.qp/->honeysql [:proton LocalDate]
  [_ ^java.time.LocalDate t]
  [:'parseDateTimeBestEffort t])

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
  (map (fn [arg] [:'toFloat64 (sql.qp/->honeysql :proton arg)]) args))

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
  [:log10 (sql.qp/->honeysql driver field)])

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
  [:'stddevPop (sql.qp/->honeysql driver field)])

(defmethod sql.qp/->honeysql [:proton :median]
  [driver [_ field]]
  [:'median (sql.qp/->honeysql driver field)])

;; Substring does not work for Enums, so we need to cast to String
(defmethod sql.qp/->honeysql [:proton :substring]
  [driver [_ arg start length]]
  (let [str [:'toString (sql.qp/->honeysql driver arg)]]
    (if length
      [:'substring str
       (sql.qp/->honeysql driver start)
       (sql.qp/->honeysql driver length)]
      [:'substring str
       (sql.qp/->honeysql driver start)])))

(defmethod sql.qp/->honeysql [:proton :var]
  [driver [_ field]]
  [:'varPop (sql.qp/->honeysql driver field)])

(defmethod sql.qp/->float :proton
  [_ value]
  [:'toFloat64 value])

(defmethod sql.qp/->honeysql [:proton :value]
  [driver value]
  (let [[_ value {base-type :base_type}] value]
    (when (some? value)
      (condp #(isa? %2 %1) base-type
        :type/IPAddress [:'to_ipv4 value]
        (sql.qp/->honeysql driver value)))))

;; the filter criterion reads "is empty"
;; also see desugar.clj
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

;; the filter criterion reads "not empty"
;; also see desugar.clj
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
       [:= [:'notEmpty hsql-field] 1]]
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

;; The following lines make sure we call lowerUTF8 instead of lower
(defn- ch-like-clause
  [driver field value options]
  (if (get options :case-sensitive true)
    [:like field (sql.qp/->honeysql driver value)]
    [:like [:'lowerUTF8 field]
     (sql.qp/->honeysql driver (update value 1 metabase.util/lower-case-en))]))

(s/defn ^:private update-string-value :- mbql.s/value
  [value :- (s/constrained mbql.s/value #(string? (second %)) ":string value") f]
  (update value 1 f))

(defmethod sql.qp/->honeysql [:proton :contains]
  [driver [_ field value options]]
  (ch-like-clause driver
                  (sql.qp/->honeysql driver field)
                  (update-string-value value #(str \% % \%))
                  options))

(defn- proton-string-fn
  [fn-name field value options]
  (let [field (sql.qp/->honeysql :proton field)
        value (sql.qp/->honeysql :proton value)]
    (if (get options :case-sensitive true)
      [fn-name field value]
      [fn-name [:'lowerUTF8 field] (metabase.util/lower-case-en value)])))

(defmethod sql.qp/->honeysql [:proton :starts-with]
  [_ [_ field value options]]
  (proton-string-fn :'starts_with field value options))

(defmethod sql.qp/->honeysql [:proton :ends-with]
  [_ [_ field value options]]
  (proton-string-fn :'ends_with field value options))

;; FIXME: there are still many failing tests that prevent us from turning this feature on
;; (defmethod sql.qp/->honeysql [:proton :convert-timezone]
;;   [driver [_ arg target-timezone source-timezone]]
;;   (let [expr          (sql.qp/->honeysql driver (cond-> arg (string? arg) u.date/parse))
;;         with-tz-info? (h2x/is-of-type? expr #"(?:nullable\(|lowcardinality\()?(datetime64\(\d, {0,1}'.*|datetime\(.*)")
;;         _             (sql.u/validate-convert-timezone-args with-tz-info? target-timezone source-timezone)
;;         inner         (if (not with-tz-info?) [:'toTimeZone expr source-timezone] expr)]
;;     [:'toTimeZone inner target-timezone]))

;; We do not have Time data types, so we cheat a little bit
(defmethod sql.qp/cast-temporal-string [:proton :Coercion/ISO8601->Time]
  [_driver _special_type expr]
  [:'parse_datetime_best_effort [:'concat "1970-01-01T" expr]])

(defmethod sql.qp/cast-temporal-byte [:proton :Coercion/ISO8601->Time]
  [_driver _special_type expr]
  expr)

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

(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/TIMESTAMP]
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (let [^java.time.LocalDateTime r (.getObject rs i LocalDateTime)]
      (cond (nil? r) nil
            (= (.toLocalDate r) (t/local-date 1970 1 1)) (.toLocalTime r)
            :else r))))

;; FIXME: should be just (.getObject rs i OffsetDateTime)
;; still blocked by many failing tests (see `sql.qp/->honeysql [:proton :convert-timezone]` as well)
(defmethod sql-jdbc.execute/read-column-thunk [:proton Types/TIMESTAMP_WITH_TIMEZONE]
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (when-let [s (.getString rs i)]
      (u.date/parse s))))

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
  [_ ^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (fn []
    (when-let [arr (.getArray rs i)]
      (let [inner (.getArray arr)]
        (cond
          ;; Booleans are returned as just bytes
          (bytes? inner)
          (str "[" (str/join ", " (map #(if (= 1 %) "true" "false") inner)) "]")
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
  (format "to_date('%s')" (t/format "yyyy-MM-dd" t)))

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
          (if (zero? (.getNano t)) "parse_datetime_best_effort" "parse_datetime64_best_effort")
          (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)))

(defmethod unprepare/unprepare-value [:proton ZonedDateTime]
  [_ t]
  (format "'%s'" (t/format "yyyy-MM-dd HH:mm:ss.SSSZZZZZ" t)))