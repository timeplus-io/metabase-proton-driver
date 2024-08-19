(defproject metabase/proton-driver "1.50.3"
  :description "Timeplus Proton driver for Metabase"
  :license "Apache License 2.0"
  :url "http://github.com/timeplus-io/metabase-proton-driver"
  :min-lein-version "2.5.0"

  :dependencies
  [[com.timeplus/proton-jdbc "0.6.0"]
   [clojure.java-time "0.3.2"]
  ]
  
  :repositories [
                ["project" "file:repo"]]
  :aliases
  {"test"       ["with-profile" "test"]}


  :profiles
  {:provided
   {:dependencies [[metabase-core "1.40"]]}

   :uberjar
   {:auto-clean    true
    :aot           :all
    :javac-options ["-target" "1.8", "-source" "1.8"]
    :target-path   "target/%s"
    :jvm-opts      ["-Dclojure.compiler.direct-linking=true"]
    :uberjar-name  "proton.metabase-driver.jar"}})