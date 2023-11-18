(defproject metabase/proton-driver "0.0.1"
  :min-lein-version "2.5.0"

  :dependencies
  [[com.timeplus/proton-jdbc "0.4.0"]
   [clojure.java-time "0.3.2"]
  ]

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