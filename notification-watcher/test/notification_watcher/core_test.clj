(ns notification-watcher.core-test
  (:require [clojure.test :refer :all]
            [notification-watcher.core :refer :all]
            [clj-http.fake :as fake]
            [clojure.string :as str]
            [cheshire.core :as cheshire]
            [ring.adapter.jetty :as jetty])
  (:import [java.util.concurrent Future TimeoutException]
           [java.net ConnectException SocketTimeoutException BindException UnknownHostException]))

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
                                    nil))]
      (is (thrown-with-msg? Exception #"GUPSHUP_APP_ID não pode estar vazio"
                            (validate-config!)))))

  (testing "validate-config! with missing GUPSHUP_API_TOKEN"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" nil
                                    nil))]
      (is (thrown-with-msg? Exception #"GUPSHUP_API_TOKEN não configurado"
                            (validate-config!)))))

  (testing "validate-config! with empty GUPSHUP_API_TOKEN"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" ""
                                    nil))]
      (is (thrown-with-msg? Exception #"GUPSHUP_API_TOKEN não pode estar vazio"
                            (validate-config!)))))

  (testing "validate-config! with invalid INTERVAL_SECONDS"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" "valid-token"
                                    "INTERVAL_SECONDS" "not-a-number"
                                    nil))]
      (is (thrown-with-msg? Exception #"INTERVAL_SECONDS deve ser um número válido"
                            (validate-config!)))))

  (testing "validate-config! with negative INTERVAL_SECONDS"
    (with-redefs [System/getenv (fn [var]
                                  (case var
                                    "GUPSHUP_APP_ID" "valid-app-id"
                                    "GUPSHUP_API_TOKEN" "valid-token"
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
        (is (= 300 (:interval-seconds config))))))) ; default

;; =============================================================================
;; TESTES DE INICIALIZAÇÃO E SISTEMA
;; =============================================================================

(deftest system-initialization-test
  (testing "safe-main with valid configuration"
    (with-redefs [validate-config! (fn [] {:app-id "test-app-id", :token "test-token", :mock-mode? true, :interval-seconds 60})
                  start-watcher-loop! (fn [config] :watcher-started)
                  start-web-server! (fn [] :server-started)
                  setup-shutdown-hook (fn [signal] :hook-setup)]
      (let [output (with-out-str (safe-main))]
        (is (some? (re-find #"✅ Configuração validada" output)))
        (is (some? (re-find #"✅ Servidor web iniciado" output)))
        (is (some? (re-find #"✅ Watcher iniciado" output))))))

  (testing "safe-main with invalid configuration"
    (with-redefs [validate-config! (fn [] (throw (Exception. "Config Invalida")))
                  System/exit (fn [code] (throw (Exception. (str "System.exit called with code: " code))))]
      (is (thrown-with-msg? Exception #"System.exit called with code: 1"
                            (safe-main)))))

  (testing "safe-main with server startup failure"
    (with-redefs [validate-config! (fn [] {:app-id "test-app-id", :token "test-token"})
                  start-web-server! (fn [] (throw (Exception. "Port 8080 já está em uso")))
                  System/exit (fn [code] (throw (Exception. (str "System.exit called with code: " code))))]
      (is (thrown-with-msg? Exception #"System.exit called with code: 1"
                            (safe-main)))))

  (testing "safe-main with watcher startup failure"
    (with-redefs [validate-config! (fn [] {:app-id "test-app-id", :token "test-token"})
                  start-web-server! (fn [] :server-started)
                  start-watcher-loop! (fn [config] (throw (Exception. "Falha ao iniciar thread do watcher")))
                  System/exit (fn [code] (throw (Exception. (str "System.exit called with code: " code))))]
      (is (thrown-with-msg? Exception #"System.exit called with code: 1"
                            (safe-main))))))

;; =============================================================================
;; TESTES DE SERVIDOR WEB E ENDPOINTS
;; =============================================================================

(deftest web-server-test
  (testing "start-web-server! with available port"
    (with-redefs [jetty/run-jetty (fn [handler opts]
                                    (is (= 8080 (:port opts)))
                                    (is (= false (:join? opts)))
                                    {:server :mock-server})]
      (let [server (start-web-server!)]
        (is (= {:server :mock-server} server)))))

  (testing "start-web-server! with port already in use"
    (with-redefs [jetty/run-jetty (fn [handler opts]
                                    (throw (BindException. "Address already in use")))]
      (is (thrown-with-msg? Exception #"Falha ao iniciar servidor web na porta 8080"
                            (start-web-server!)))))

  (testing "start-web-server! with custom port from environment"
    (with-redefs [System/getenv (fn [var] (when (= var "PORT") "3000"))
                  jetty/run-jetty (fn [handler opts]
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
        (is (= true (get-in response [:dependencies :gupshup-api :available])))))

    (with-redefs [can-connect-to-gupshup? (fn [] false)]
      (let [response (health-check)]
        (is (= :degraded (:status response)))
        (is (= false (get-in response [:dependencies :gupshup-api :available]))))))

  (testing "app-handler returns correct response"
    (let [response (app-handler {})]
      (is (= (:status response) 200))
      (is (= (get-in response [:headers "Content-Type"]) "application/json; charset=utf-8"))
      ; Assuming health-check is the root handler
      (is (map? (cheshire/parse-string (:body response) true))))))

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
       (fn [request] (throw (UnknownHostException. "api.gupshup.io")))}
      (is (= false (can-connect-to-gupshup?)))))

  (testing "can-connect-to-gupshup? with server error"
    (fake/with-fake-routes
      {"https://api.gupshup.io/health"
       (fn [request] {:status 500 :headers {} :body "Internal Server Error"})}
      (is (= false (can-connect-to-gupshup?))))))

;; =============================================================================
;; TESTES DE LÓGICA PRINCIPAL (ORIGINAIS E APRIMORADOS)
;; =============================================================================

(deftest fetch-templates-logic-test
  (testing "fetch-templates in mock mode (MOCK_MODE=true)"
    (with-redefs [mock-mode? true]
      (let [templates (fetch-templates "test-app-id" "test-token")]
        (is (= templates mock-templates-com-mudanca))
        (is (str/includes? (with-out-str (fetch-templates "test-app-id" "test-token"))
                           "Rodando em MOCK_MODE. Usando dados de mock."))))))

  (testing "fetch-templates with successful API call"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\",\"category\":\"MARKETING\"}]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [{:elementName "tpl1" :category "MARKETING"}] templates))
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning non-200 status"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 401 :headers {} :body "{\"status\":\"error\",\"message\":\"Unauthorized\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (nil? templates))
          (is (some? (re-find #"\[WORKER\] Erro ao buscar templates da Gupshup. Status HTTP: 401" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning 200 but unexpected body structure"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"message\":\"Unexpected success format\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates)) ; Expecting empty list due to get-in default
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\)." (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with network error (ConnectException)"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] (throw (ConnectException. "Connection refused by fake route")))}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? templates))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))
          (is (some? (re-find #"Tipo da exceção: class java.net.ConnectException" output)))
          (is (some? (re-find #"Mensagem: Connection refused by fake route" output)))))))

  (testing "fetch-templates with timeout error (SocketTimeoutException)"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] (throw (SocketTimeoutException. "Read timed out")))}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? templates))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA" output)))
          (is (some? (re-find #"Tipo da exceção: class java.net.SocketTimeoutException" output)))
          (is (some? (re-find #"Mensagem: Read timed out" output)))))))

  (testing "fetch-templates with successful API call and empty templates list"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates))))))

  (testing "fetch-templates with API returning 200 but malformed JSON"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\""})} ; Malformed
        (let [output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? (fetch-templates "test-app-id" "test-token")))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA" output)))))))

  (testing "fetch-templates with API returning 200 OK but templates field is null"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":null}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates)))))))

(deftest check-for-changes-test
  (testing "check-for-changes with templates having category changes"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl1", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl2", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output)))
        (is (some? (re-find #"Categoria Antiga: UTILITY" output)))
        (is (some? (re-find #"Nova Categoria: MARKETING" output))))))

  (testing "check-for-changes with no templates having category changes"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl1", :category "MARKETING", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl2", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Nenhum template ativo com mudança de categoria encontrado." output))))))

  (testing "check-for-changes with a mix of templates, including FAILED status"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl1", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl2", :category "UTILITY", :status "FAILED"} ; Should be ignored
                                     {:id "3", :elementName "tpl3", :category "AUTHENTICATION", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 3." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output))))))

  (testing "check-for-changes with template list containing non-map elements"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl1", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                     "a-string-template"
                                     nil
                                     {:id "2", :elementName "tpl2", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 4." output)))
        (is (some? (re-find #"\[WORKER\] Número de itens não-mapa ignorados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output))))))

  (testing "check-for-changes with an empty list of templates from fetch-templates"
    (with-redefs [fetch-templates (fn [app-id token] [])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 0." output)))
        (is (some? (re-find #"\[WORKER\] Nenhum template ativo com mudança de categoria encontrado." output))))))

  (testing "check-for-changes when fetch-templates returns nil (simulating API error)"
    (with-redefs [fetch-templates (fn [app-id token] nil)]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Não foi possível obter a lista de templates para verificação." output)))))))

;; =============================================================================
;; TESTES DE CONCORRÊNCIA E CICLO DE VIDA DO WATCHER
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
                                   (is (= 1000 ms)) ; Verifica intervalo de 1s
                                   (Thread/sleep 10))] ; Sleep curto para o teste rodar rápido
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
        (is (every? #(= "Simulated error" %) @exceptions))))))

;; =============================================================================
;; TESTES DE RECURSOS E PERFORMANCE
;; =============================================================================

(deftest resource-management-test
  (testing "get-memory-usage returns valid data"
    (let [memory-info (get-memory-usage)]
      (is (map? memory-info))
      (is (number? (:used-mb memory-info)))
      (is (number? (:total-mb memory-info)))
      (is (number? (:max-mb memory-info)))
      (is (>= (:total-mb memory-info) (:used-mb memory-info)))))

  (testing "memory-threshold-exceeded? with normal usage"
    (with-redefs [get-memory-usage (fn [] {:used-mb 500, :max-mb 1000})]
      (is (= false (memory-threshold-exceeded? 0.8))))) ; 50% usage < 80% threshold

  (testing "memory-threshold-exceeded? with high usage"
    (with-redefs [get-memory-usage (fn [] {:used-mb 900, :max-mb 1000})]
      (is (= true (memory-threshold-exceeded? 0.8))))) ; 90% usage > 80% threshold

  (testing "fetch-templates with large dataset simulation"
    (with-redefs [mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request]
           (let [large-template-list (vec (repeatedly 1000
                                                      #(hash-map :elementName (str "template-" (rand-int 10000))
                                                                 :category "MARKETING"
                                                                 :status "ACTIVE")))]
             {:status 200
              :headers {}
              :body (cheshire/generate-string {:status "success"
                                               :templates large-template-list})}))}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= 1000 (count templates)))
          (is (every? map? templates)))))))

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
      (.run shutdown-hook) ; Executa a lógica da thread diretamente
      (is (= true @shutdown-signal)))))

;; =============================================================================
;; TESTES DE LOGGING E MONITORAMENTO
;; =============================================================================

(deftest logging-test
  (testing "log-error with different exception types"
    (let [connect-ex (ConnectException. "Connection refused")
          generic-ex (Exception. "Generic error")]
      (let [output (with-out-str (log-error connect-ex))]
        (is (some? (re-find #"❌ ERRO CRÍTICO:" output)))
        (is (some? (re-find #"Connection refused" output))))
      (let [output (with-out-str (log-error generic-ex))]
        (is (some? (re-find #"❌ ERRO CRÍTICO:" output)))
        (is (some? (re-find #"Generic error" output))))))

  (testing "log-startup-info"
    (let [output (with-out-str (log-startup-info))]
      (is (some? (re-find #"🚀 NOTIFICATION WATCHER" output)))
      (is (some? (re-find #"Versão:" output)))
      (is (some? (re-find #"Ambiente:" output)))))

  (testing "log-config-info"
    (let [config {:app-id "test-app" :token "token-secret" :mock-mode? true :interval-seconds 60}
          output (with-out-str (log-config-info config))]
      (is (some? (re-find #"📋 CONFIGURAÇÃO:" output)))
      (is (some? (re-find #"App ID: test-app" output)))
      (is (some? (re-find #"Token: \*\*\*\*\*\*\*\*\*\*\*\*" output))) ; Verifica se o token foi ofuscado
      (is (not (str/includes? output "token-secret")))
      (is (some? (re-find #"Mock Mode: true" output)))
      (is (some? (re-find #"Intervalo: 60 segundos" output))))))
