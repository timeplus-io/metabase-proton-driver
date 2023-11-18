(ns metabase.driver.proton-introspection
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [metabase.driver :as driver]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.util :as u])
  (:import (java.sql DatabaseMetaData)))

(set! *warn-on-reflection* true)

(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"array" :type/Array]
    [#"bool" :type/Boolean]
    [#"datetime64" :type/DateTime]
    [#"datetime" :type/DateTime]
    [#"date" :type/Date]
    [#"date32" :type/Date]
    [#"decimal" :type/Decimal]
    [#"enum8" :type/Text]
    [#"enum16" :type/Text]
    [#"fixedstring" :type/TextLike]
    [#"float32" :type/Float]
    [#"float64" :type/Float]
    [#"int8" :type/Integer]
    [#"int16" :type/Integer]
    [#"int32" :type/Integer]
    [#"int64" :type/BigInteger]
    ;; FIXME: set it back to IPAddress when 0.48 is out, as it should resolve IPAddress and other semantic types checks issues
    [#"ipv4" :type/TextLike]
    [#"ipv6" :type/TextLike]
    [#"map" :type/Dictionary]
    [#"string" :type/Text]
    [#"tuple" :type/*]
    [#"uint8" :type/Integer]
    [#"uint16" :type/Integer]
    [#"uint32" :type/Integer]
    [#"uint64" :type/BigInteger]
    [#"uuid" :type/UUID]]))

(defn- normalize-db-type
  [db-type]
  (cond
    ;; LowCardinality
    (str/starts-with? db-type "lowcardinality")
    (normalize-db-type (subs db-type 15 (- (count db-type) 1)))
    ;; Nullable
    (str/starts-with? db-type "nullable")
    (normalize-db-type (subs db-type 9 (- (count db-type) 1)))
    ;; DateTime64
    (str/starts-with? db-type "datetime64")
    :type/DateTime ;; FIXME: should be type/DateTimeWithTZ (#200)
    ;; DateTime
    (str/starts-with? db-type "datetime")
    :type/DateTime ;; FIXME: should be type/DateTimeWithTZ (#200)
    ;; Enum*
    (str/starts-with? db-type "enum")
    :type/Text
    ;; Map
    (str/starts-with? db-type "map")
    :type/Dictionary
    ;; Tuple
    (str/starts-with? db-type "tuple")
    :type/*
    ;; SimpleAggregateFunction
    (str/starts-with? db-type "simpleaggregatefunction")
    (normalize-db-type (subs db-type (+ (str/index-of db-type ",") 2) (- (count db-type) 1)))
    ;; _
    :else (or (database-type->base-type (keyword db-type)) :type/*)))

;; Enum8(UInt8) -> :type/Text, DateTime64(Europe/Amsterdam) -> :type/DateTime,
;; Nullable(DateTime) -> :type/DateTime, SimpleAggregateFunction(sum, Int64) -> :type/BigInteger, etc
(defmethod sql-jdbc.sync/database-type->base-type :proton
  [_ database-type]
  (normalize-db-type (subs (str database-type) 1)))

(defmethod sql-jdbc.sync/excluded-schemas :proton [_]
  #{"system" "information_schema" "INFORMATION_SCHEMA"})

(def ^:private allowed-table-types
  (into-array String
              ["STREAM","EXTERNAL STREAM","TABLE" "VIEW" "FOREIGN TABLE" "REMOTE TABLE" "DICTIONARY"
               "MATERIALIZED VIEW" "MEMORY TABLE" "LOG TABLE"]))

(defn- tables-set
  [tables]
  (set
   (for [table tables]
     (let [remarks (:remarks table)]
       {:name (:table_name table)
        :schema (:table_schem table)
        :description (when-not (str/blank? remarks) remarks)}))))

(defn- get-tables-from-metadata
  [^DatabaseMetaData metadata schema-pattern]
  (.getTables metadata
              nil            ; catalog - unused in the source code there
              schema-pattern
              "%"            ; tablePattern "%" = match all tables
              allowed-table-types))

(defn- not-inner-mv-table?
  [table]
  (not (str/starts-with? (:table_name table) ".inner")))

(defn- ->spec
  [db]
  (if (u/id db)
    (sql-jdbc.conn/db->pooled-connection-spec db) db))

(defn- get-all-tables
  [db]
  (jdbc/with-db-metadata [metadata (->spec db)]
    (->> (get-tables-from-metadata metadata "%")
         (jdbc/metadata-result)
         (vec)
         (filter #(and
                   (not (contains? (sql-jdbc.sync/excluded-schemas :proton) (:table_schem %)))
                   (not-inner-mv-table? %)))
         (tables-set))))

;; Strangely enough, the tests only work with :db keyword,
;; but the actual sync from the UI uses :dbname
(defn- get-db-name
  [db]
  (or (get-in db [:details :dbname])
      (get-in db [:details :db])))

(defn- get-tables-in-dbs [db-or-dbs]
  (->> (for [db (as-> (or (get-db-name db-or-dbs) "default") dbs
                  (str/split dbs #" ")
                  (remove empty? dbs)
                  (map (comp #(ddl.i/format-name :proton %) str/trim) dbs))]
         (jdbc/with-db-metadata [metadata (->spec db-or-dbs)]
           (jdbc/metadata-result
            (get-tables-from-metadata metadata db))))
       (apply concat)
       (filter not-inner-mv-table?)
       (tables-set)))

(defmethod driver/describe-database :proton
  [_ {{:keys [scan-all-databases]}
      :details :as db}]
  {:tables
   (if
    (boolean scan-all-databases)
     (get-all-tables db)
     (get-tables-in-dbs db))})

(defn- ^:private is-db-required?
  [field]
  (not (str/starts-with? (get-in field [:database-type]) "nullable")))

(defmethod driver/describe-table :proton
  [_ database table]
  (let [table-metadata (sql-jdbc.sync/describe-table :proton database table)
        filtered-fields (for [field (:fields table-metadata)
                              :let [updated-field (update-in field [:database-required]
                                                             (fn [_] (is-db-required? field)))]
                              ;; Skip all AggregateFunction (but keeping SimpleAggregateFunction) columns
                              ;; JDBC does not support that and it crashes the data browser
                              :when (not (re-matches #"^aggregatefunction\(.+$"
                                                     (get field :database-type)))]
                          updated-field)]
    (merge table-metadata {:fields (set filtered-fields)})))