(defproject notification-watcher "0.1.0-SNAPSHOT"
  :description "Serviço para monitorar templates da Gupshup e notificar sobre mudanças."
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.json "2.5.0"]
                 
                 ;; Cliente HTTP para consumir a API da Gupshup
                 [clj-http "3.12.3"]

                 ;; Parser JSON (necessário para :as :json com clj-http)
                 [cheshire "5.12.0"]

                 ;; Servidor web e roteamento (abordagem moderna)
                 [http-kit "2.7.0"]
                 [metosin/reitit-ring "0.7.0-alpha7"]]

  :profiles {:dev {:dependencies [[clj-http-fake "1.0.4"]]} ;; Added for mocking HTTP requests
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}}

  :main ^:skip-aot notification-watcher.core
  :target-path "target/%s")
;; Removed duplicate :profiles key that was causing the error
