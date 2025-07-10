(ns notification-watcher.core-test
  (:require [clojure.test :refer :all]
            [notification-watcher.core :refer :all]
            [clj-http.fake :as fake]
            [clojure.string :as str])
  (:import [java.util.concurrent Future TimeoutException]
           [java.net ConnectException SocketTimeoutException]))

;; =============================================================================
;; TESTES DE CONFIGURAÇÃO E AMBIENTE
;; =============================================================================

(deftest configuration-validation-test
  (testing "validate-config! with missing GUPSHUP_APP_ID"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" nil
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    "MOCK_MODE" "false"
                                    "INTERVAL_SECONDS" "60"
                                    nil))]
      (is (thrown-with-msg? Exception #"GUPSHUP_APP_ID não configurado"
                            (validate-config!)))))

  (testing "validate-config! with empty GUPSHUP_APP_ID"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" ""
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    "MOCK_MODE" "false"
                                    "INTERVAL_SECONDS" "60"
                                    nil))]
      (is (thrown-with-msg? Exception #"GUPSHUP_APP_ID não pode estar vazio"
                            (validate-config!)))))

  (testing "validate-config! with missing GUPSHUP_API_TOKEN"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" nil
                                    "MOCK_MODE" "false"
                                    "INTERVAL_SECONDS" "60"
                                    nil))]
      (is (thrown-with-msg? Exception #"GUPSHUP_API_TOKEN não configurado"
                            (validate-config!)))))

  (testing "validate-config! with empty GUPSHUP_API_TOKEN"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" ""
                                    "MOCK_MODE" "false"
                                    "INTERVAL_SECONDS" "60"
                                    nil))]
      (is (thrown-with-msg? Exception #"GUPSHUP_API_TOKEN não pode estar vazio"
                            (validate-config!)))))

  (testing "validate-config! with invalid INTERVAL_SECONDS"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    "MOCK_MODE" "false"
                                    "INTERVAL_SECONDS" "not-a-number"
                                    nil))]
      (is (thrown-with-msg? Exception #"INTERVAL_SECONDS deve ser um número válido"
                            (validate-config!)))))

  (testing "validate-config! with negative INTERVAL_SECONDS"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    "MOCK_MODE" "false"
                                    "INTERVAL_SECONDS" "-10"
                                    nil))]
      (is (thrown-with-msg? Exception #"INTERVAL_SECONDS deve ser positivo"
                            (validate-config!)))))

  (testing "validate-config! with invalid MOCK_MODE"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    "MOCK_MODE" "maybe"
                                    "INTERVAL_SECONDS" "60"
                                    nil))]
      (is (thrown-with-msg? Exception #"MOCK_MODE deve ser 'true' ou 'false'"
                            (validate-config!)))))

  (testing "validate-config! with valid configuration"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    "MOCK_MODE" "true"
                                    "INTERVAL_SECONDS" "60"
                                    nil))]
      (is (= {:app-id "valid-app-id"
              :token "valid-token"
              :mock-mode? true
              :interval-seconds 60}
             (validate-config!)))))

  (testing "validate-config! with default values"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    ;; MOCK_MODE e INTERVAL_SECONDS não definidos
                                    nil))]
      (let [config (validate-config!)]
        (is (= "valid-app-id" (:app-id config)))
        (is (= "valid-token" (:token config)))
        (is (= false (:mock-mode? config))) ; default
        (is (= 300 (:interval-seconds config)))))) ; default

;; =============================================================================
;; TESTES DE INICIALIZAÇÃO E SISTEMA
;; =============================================================================

(deftest system-initialization-test
  (testing "safe-main with valid configuration"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "test-app-id"
                                    "GUPSHUP_API_TOKEN" "test-token"
                                    "MOCK_MODE" "true"
                                    "INTERVAL_SECONDS" "60"
                                    nil))
                  start-watcher-loop! (fn [] :started)
                  start-web-server! (fn [] :server-started)]
      (let [output (with-out-str (safe-main))]
        (is (some? (re-find #"✅ Configuração validada" output)))
        (is (some? (re-find #"✅ Servidor web iniciado" output)))
        (is (some? (re-find #"✅ Watcher iniciado" output))))))

  (testing "safe-main with invalid configuration"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" nil
                                    "GUPSHUP_API_TOKEN" "test-token"
                                    nil))
                  System/exit (fn [code] (throw (Exception. (str "System.exit called with code: " code))))]
      (is (thrown-with-msg? Exception #"System.exit called with code: 1"
                            (safe-main)))))

  (testing "safe-main with server startup failure"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "test-app-id"
                                    "GUPSHUP_API_TOKEN" "test-token"
                                    "MOCK_MODE" "true"
                                    "INTERVAL_SECONDS" "60"
                                    nil))
                  start-web-server! (fn [] (throw (Exception. "Port 8080 já está em uso")))
                  System/exit (fn [code] (throw (Exception. (str "System.exit called with code: " code))))]
      (is (thrown-with-msg? Exception #"System.exit called with code: 1"
                            (safe-main)))))

  (testing "safe-main with watcher startup failure"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "test-app-id"
                                    "GUPSHUP_API_TOKEN" "test-token"
                                    "MOCK_MODE" "true"
                                    "INTERVAL_SECONDS" "60"
                                    nil))
                  start-web-server! (fn [] :server-started)
                  start-watcher-loop! (fn [] (throw (Exception. "Falha ao iniciar thread do watcher")))
                  System/exit (fn [code] (throw (Exception. (str "System.exit called with code: " code))))]
      (is (thrown-with-msg? Exception #"System.exit called with code: 1"
                            (safe-main))))))

;; =============================================================================
;; TESTES DE SERVIDOR WEB
;; =============================================================================

(deftest web-server-test
  (testing "start-web-server! with available port"
    (with-redefs [ring.adapter.jetty/run-jetty (fn [handler opts]
                                                 (is (= 8080 (:port opts)))
                                                 (is (= false (:join? opts)))
                                                 {:server :mock-server})]
      (let [server (start-web-server!)]
        (is (= {:server :mock-server} server)))))

  (testing "start-web-server! with port already in use"
    (with-redefs [ring.adapter.jetty/run-jetty (fn [handler opts]
                                                 (throw (java.net.BindException. "Address already in use")))]
      (is (thrown-with-msg? Exception #"Falha ao iniciar servidor web na porta 8080"
                            (start-web-server!)))))

  (testing "start-web-server! with custom port from environment"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "PORT" "3000"
                                    nil))
                  ring.adapter.jetty/run-jetty (fn [handler opts]
                                                 (is (= 3000 (:port opts)))
                                                 {:server :mock-server})]
      (let [server (start-web-server!)]
        (is (= {:server :mock-server} server)))))

  (testing "health-check endpoint"
    (let [response (health-check)]
      (is (contains? response :status))
      (is (contains? response :timestamp))
      (is (contains? response :version))
      (is (contains? response :uptime-ms))
      (is (number? (:timestamp response)))
      (is (number? (:uptime-ms response)))))

  (testing "health-check with gupshup connectivity"
    (with-redefs [can-connect-to-gupshup? (fn [] true)]
      (let [response (health-check)]
        (is (= :healthy (:status response)))
        (is (= true (:gupshup-available response)))))

    (with-redefs [can-connect-to-gupshup? (fn [] false)]
      (let [response (health-check)]
        (is (= :degraded (:status response)))
        (is (= false (:gupshup-available response))))))

;; =============================================================================
;; TESTES DE CONECTIVIDADE E REDE
;; =============================================================================

(deftest network-connectivity-test
  (testing "can-connect-to-gupshup? with successful connection"
    (fake/with-fake-routes
      {"https://api.gupshup.io/health"
       (fn [request] {:status 200 :headers {} :body "OK"})}
      (is (= true (can-connect-to-gupshup?)))))

  (testing "can-connect-to-gupshup? with connection timeout"
    (fake/with-fake-routes
      {"https://api.gupshup.io/health"
       (fn [request] (throw (SocketTimeoutException. "Read timed out")))}
      (is (= false (can-connect-to-gupshup?)))))

  (testing "can-connect-to-gupshup? with DNS resolution failure"
    (fake/with-fake-routes
      {"https://api.gupshup.io/health"
       (fn [request] (throw (java.net.UnknownHostException. "api.gupshup.io")))}
      (is (= false (can-connect-to-gupshup?)))))

  (testing "can-connect-to-gupshup? with server error"
    (fake/with-fake-routes
      {"https://api.gupshup.io/health"
       (fn [request] {:status 500 :headers {} :body "Internal Server Error"})}
      (is (= false (can-connect-to-gupshup?)))))

;; =============================================================================
;; TESTES DE CONCORRÊNCIA E RECURSOS
;; =============================================================================

(deftest concurrency-test
  (testing "watcher-loop with graceful shutdown"
    (let [shutdown-signal (atom false)
          iterations (atom 0)]
      (with-redefs [check-for-changes (fn [app-id token]
                                        (swap! iterations inc)
                                        (when (>= @iterations 3)
                                          (reset! shutdown-signal true)))
                    Thread/sleep (fn [ms] 
                                   (is (= 1000 ms)) ; Verifica intervalo
                                   (Thread/sleep 10))] ; Sleep mais curto para teste
        (safe-watcher-loop! "test-app-id" "test-token" 1 shutdown-signal)
        (is (= 3 @iterations)))))

  (testing "watcher-loop with exception handling"
    (let [shutdown-signal (atom false)
          iterations (atom 0)
          exceptions (atom [])]
      (with-redefs [check-for-changes (fn [app-id token]
                                        (swap! iterations inc)
                                        (if (< @iterations 3)
                                          (throw (Exception. "Simulated error"))
                                          (reset! shutdown-signal true)))
                    Thread/sleep (fn [ms] (Thread/sleep 10))
                    log-error (fn [e] (swap! exceptions conj (.getMessage e)))]
        (safe-watcher-loop! "test-app-id" "test-token" 1 shutdown-signal)
        (is (= 3 @iterations))
        (is (= 2 (count @exceptions)))
        (is (every? #(= "Simulated error" %) @exceptions)))))

  (testing "watcher-loop with resource monitoring"
    (let [shutdown-signal (atom false)
          memory-usage (atom [])]
      (with-redefs [check-for-changes (fn [app-id token]
                                        (swap! memory-usage conj (get-memory-usage))
                                        (when (>= (count @memory-usage) 2)
                                          (reset! shutdown-signal true)))
                    Thread/sleep (fn [ms] (Thread/sleep 10))]
        (safe-watcher-loop! "test-app-id" "test-token" 1 shutdown-signal)
        (is (= 2 (count @memory-usage)))
        (is (every? map? @memory-usage))
        (is (every? #(contains? % :used-memory) @memory-usage))
        (is (every? #(contains? % :free-memory) @memory-usage)))))

;; =============================================================================
;; TESTES DE RECURSOS E PERFORMANCE
;; =============================================================================

(deftest resource-management-test
  (testing "get-memory-usage returns valid data"
    (let [memory-info (get-memory-usage)]
      (is (map? memory-info))
      (is (number? (:used-memory memory-info)))
      (is (number? (:free-memory memory-info)))
      (is (number? (:total-memory memory-info)))
      (is (>= (:total-memory memory-info) (:used-memory memory-info)))))

  (testing "check-memory-threshold with normal usage"
    (with-redefs [get-memory-usage (fn [] {:used-memory 500 :total-memory 1000})]
      (is (= false (memory-threshold-exceeded?))))) ; 50% usage

  (testing "check-memory-threshold with high usage"
    (with-redefs [get-memory-usage (fn [] {:used-memory 900 :total-memory 1000})]
      (is (= true (memory-threshold-exceeded?))))) ; 90% usage

  (testing "fetch-templates with large dataset simulation"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request]
           (let [large-template-list (vec (repeatedly 1000 
                                                     #(hash-map :elementName (str "template-" (rand-int 10000))
                                                               :category "MARKETING"
                                                               :status "ACTIVE")))]
             {:status 200 
              :headers {} 
              :body (cheshire.core/generate-string {:status "success" 
                                                   :templates large-template-list})}))}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= 1000 (count templates)))
          (is (every? map? templates))
          (is (every? #(contains? % :elementName) templates))))))

;; =============================================================================
;; TESTES DE INTEGRAÇÃO
;; =============================================================================

(deftest integration-test
  (testing "full workflow with mocked external dependencies"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "integration-test-app-id"
                                    "GUPSHUP_API_TOKEN" "integration-test-token"
                                    "MOCK_MODE" "false"
                                    "INTERVAL_SECONDS" "1"
                                    nil))]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/integration-test-app-id"
         (fn [request]
           {:status 200 
            :headers {} 
            :body (cheshire.core/generate-string 
                   {:status "success"
                    :templates [{:id "1" :elementName "test-template" 
                                :category "MARKETING" :oldCategory "UTILITY" 
                                :status "ACTIVE"}]})})}
        (let [output (with-out-str (check-for-changes "integration-test-app-id" "integration-test-token"))]
          (is (some? (re-find #"Total de templates recebidos da API: 1" output)))
          (is (some? (re-find #"Dentre os ativos, 1 templates com mudança de categoria encontrados" output)))
          (is (some? (re-find #"Nome: test-template" output)))
          (is (some? (re-find #"Categoria Antiga: UTILITY" output)))
          (is (some? (re-find #"Nova Categoria: MARKETING" output)))))))

  (testing "full workflow with API error and recovery"
    (let [call-count (atom 0)]
      (with-redefs [System/getenv (fn [var]
                                    (case var
                                      "GUPSHUP_APP_ID" "integration-test-app-id"
                                      "GUPSHUP_API_TOKEN" "integration-test-token"
                                      "MOCK_MODE" "false"
                                      "INTERVAL_SECONDS" "1"
                                      nil))]
        (fake/with-fake-routes
          {"https://api.gupshup.io/sm/api/v1/template/list/integration-test-app-id"
           (fn [request]
             (swap! call-count inc)
             (if (< @call-count 2)
               {:status 500 :headers {} :body "Internal Server Error"}
               {:status 200 :headers {} :body (cheshire.core/generate-string {:status "success" :templates []})}))}
          (let [output1 (with-out-str (check-for-changes "integration-test-app-id" "integration-test-token"))
                output2 (with-out-str (check-for-changes "integration-test-app-id" "integration-test-token"))]
            (is (some? (re-find #"Erro ao buscar templates da Gupshup. Status HTTP: 500" output1)))
            (is (some? (re-find #"Total de templates recebidos da API: 0" output2)))
            (is (= 2 @call-count)))))))

;; =============================================================================
;; TESTES ORIGINAIS APRIMORADOS
;; =============================================================================

(deftest fetch-templates-logic-test
  (testing "fetch-templates in mock mode (MOCK_MODE=true)"
    (with-redefs [notification-watcher.core/mock-mode? true]
      (let [templates (fetch-templates "test-app-id" "test-token")]
        (is (= templates mock-templates-com-mudanca))
        (is (string? (with-out-str (fetch-templates "test-app-id" "test-token"))))))) ; Check for print output

  (testing "fetch-templates with successful API call"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\",\"category\":\"MARKETING\"}]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [{:elementName "tpl1" :category "MARKETING"}] templates))
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning non-200 status"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 401 :headers {} :body "{\"status\":\"error\",\"message\":\"Unauthorized\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (nil? templates))
          (is (some? (re-find #"\[WORKER\] Erro ao buscar templates da Gupshup. Status HTTP: 401" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning 200 but unexpected body structure"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"message\":\"Unexpected success format\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates)) ; Expecting empty list due to get-in default
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with network error (exception using fake/with-fake-routes)"
    (with-redefs [notification-watcher.core/mock-mode? false] ; Keep mock-mode false for this
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] (throw (java.net.ConnectException. "Connection refused by fake route")))}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? templates))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))
          (is (some? (re-find #"Tipo da exceção: class java.net.ConnectException" output)))
          (is (some? (re-find #"Mensagem: Connection refused by fake route" output)))))))

  (testing "fetch-templates with timeout error"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] (throw (SocketTimeoutException. "Read timed out")))}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? templates))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))
          (is (some? (re-find #"Tipo da exceção: class java.net.SocketTimeoutException" output)))
          (is (some? (re-find #"Mensagem: Read timed out" output)))))))

  ;; ... (outros testes originais permanecem iguais)
  )

(deftest check-for-changes-test
  ;; ... (testes originais permanecem iguais)
  )

;; =============================================================================
;; TESTES DE GRACEFUL SHUTDOWN
;; =============================================================================

(deftest graceful-shutdown-test
  (testing "setup-shutdown-hook creates proper shutdown hook"
    (let [shutdown-hooks (atom [])]
      (with-redefs [Runtime/getRuntime (fn [] 
                                         (reify Runtime
                                           (addShutdownHook [this thread]
                                             (swap! shutdown-hooks conj thread))))]
        (setup-shutdown-hook (atom false))
        (is (= 1 (count @shutdown-hooks)))
        (is (instance? Thread (first @shutdown-hooks))))))

  (testing "shutdown hook sets shutdown signal"
    (let [shutdown-signal (atom false)
          shutdown-hook (create-shutdown-hook shutdown-signal)]
      (.run shutdown-hook)
      (is (= true @shutdown-signal)))))

;; =============================================================================
;; TESTES DE LOGGING E MONITORAMENTO
;; =============================================================================

(deftest logging-test
  (testing "log-error with different exception types"
    (let [connect-ex (ConnectException. "Connection refused")
          timeout-ex (SocketTimeoutException. "Read timed out")
          generic-ex (Exception. "Generic error")]
      
      (let [output (with-out-str (log-error connect-ex))]
        (is (some? (re-find #"❌ ERRO CRÍTICO:" output)))
        (is (some? (re-find #"Connection refused" output))))
      
      (let [output (with-out-str (log-error timeout-ex))]
        (is (some? (re-find #"❌ ERRO CRÍTICO:" output)))
        (is (some? (re-find #"Read timed out" output))))
      
      (let [output (with-out-str (log-error generic-ex))]
        (is (some? (re-find #"❌ ERRO CRÍTICO:" output)))
        (is (some? (re-find #"Generic error" output))))))

  (testing "log-startup-info"
    (let [output (with-out-str (log-startup-info))]
      (is (some? (re-find #"🚀 NOTIFICATION WATCHER" output)))
      (is (some? (re-find #"Versão:" output)))
      (is (some? (re-find #"Ambiente:" output)))
      (is (some? (re-find #"Timestamp:" output)))))

  (testing "log-config-info"
    (let [config {:app-id "test-app" :token "***" :mock-mode? true :interval-seconds 60}
          output (with-out-str (log-config-info config))]
      (is (some? (re-find #"📋 CONFIGURAÇÃO:" output)))
      (is (some? (re-find #"App ID: test-app" output)))
      (is (some? (re-find #"Token: \*\*\*" output)))
      (is (some? (re-find #"Mock Mode: true" output)))
      (is (some? (re-find #"Intervalo: 60 segundos" output))))))
