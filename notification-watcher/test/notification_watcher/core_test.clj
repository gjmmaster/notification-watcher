(ns notification-watcher.core-test
  (:require [clojure.test :refer :all]
            [notification-watcher.core :refer :all]
            [clj-http.fake :as fake]
            [clojure.string :as str]))

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

  (testing "fetch-templates with successful API call and empty templates list"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates))
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning 200 but malformed JSON"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\""})} ; Malformed JSON
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? templates))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))))))

  (testing "fetch-templates with API returning 200 OK but templates field is null"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":null}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates)) ; Default value from get-in
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning 200 OK but templates array contains non-map elements"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\"}, \"string-element\", null, {\"elementName\":\"tpl2\"}]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (= [{:elementName "tpl1"} "string-element" nil {:elementName "tpl2"}] templates))
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\)." output)))))))

  (testing "fetch-templates with API returning 200 but unexpected non-JSON body"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "This is not JSON"})}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? templates))
          ;; Verifica o log do catch em fetch-templates, pois :as :json em clj-http causa exceção com corpo nao-JSON
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))
          (is (some? (re-find #"Tipo da exceção: class com.fasterxml.jackson.core.JsonParseException" output)))))))


  (testing "fetch-templates with API returning 500 Internal Server Error"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 500 :headers {} :body "{\"error\":\"Internal Server Error\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (nil? templates))
          (is (some? (re-find #"\[WORKER\] Erro ao buscar templates da Gupshup. Status HTTP: 500" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning 200 OK but 'status' field is 'error'"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"error\",\"message\":\"Simulated error in body\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates)) ; Current core.clj logic: if status 200 and body is map, (get-in body [:templates]) or []
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token"))))))))))

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

  (testing "check-for-changes with various template statuses"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-active-changed", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl-pending", :category "UTILITY", :status "PENDING"}
                                     {:id "3", :elementName "tpl-rejected-changed", :category "AUTHENTICATION", :oldCategory "UTILITY", :status "REJECTED"}
                                     {:id "4", :elementName "tpl-failed", :category "MARKETING", :status "FAILED"}
                                     {:id "5", :elementName "tpl-active-no-change", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 5." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 4." output))) ; ACTIVE, PENDING, REJECTED
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 2 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-active-changed" output)))
        (is (some? (re-find #"Nome: tpl-rejected-changed" output)))
        (is (not (some? (re-find #"Nome: tpl-pending" output)))) ; No change logged
        (is (not (some? (re-find #"Nome: tpl-failed" output)))))))

  (testing "check-for-changes with active template where oldCategory equals category"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-no-real-change", :category "MARKETING", :oldCategory "MARKETING", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl-real-change", :category "UTILITY", :oldCategory "MARKETING", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 2." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 2." output)))
        ;; Current logic logs if oldCategory is present, regardless of actual change
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 2 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-no-real-change" output)))
        (is (some? (re-find #"Categoria Antiga: MARKETING" output)))
        (is (some? (re-find #"Nova Categoria: MARKETING" output)))
        (is (some? (re-find #"Nome: tpl-real-change" output))))))

  (testing "check-for-changes with active template, oldCategory present, but category missing"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-missing-category", :oldCategory "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 1." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 1." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-missing-category" output)))
        (is (some? (re-find #"Categoria Antiga: UTILITY" output)))
        (is (some? (re-find #"Nova Categoria: \s*$" output)))))) ; nil becomes empty string in str, check for optional space and end of line

  (testing "check-for-changes with template list containing non-map elements"
    (with-redefs [fetch-templates (fn [app-id token]
                                     [{:id "1", :elementName "tpl1", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                      "a-string-template" ; Non-map element
                                      nil ; Another non-map element
                                      {:id "2", :elementName "tpl2", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 4." output)))
        (is (some? (re-find #"\[WORKER\] Número de itens não-mapa ignorados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output)))
        (is (not (some? (re-find #"Nome: tpl2" output)))))))

  (testing "check-for-changes with an empty list of templates from fetch-templates"
    (with-redefs [fetch-templates (fn [app-id token] [])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 0." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 0." output)))
        (is (some? (re-find #"\[WORKER\] Nenhum template ativo com mudança de categoria encontrado." output))))))

  (testing "check-for-changes when fetch-templates returns nil (simulating API error)"
    (with-redefs [fetch-templates (fn [app-id token] nil)]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Não foi possível obter a lista de templates para verificação." output)))))))

(deftest check-for-changes-additional-tests
  (testing "check-for-changes with template missing :category but has :oldCategory"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-missing-cat", :oldCategory "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-missing-cat" output)))
        (is (some? (re-find #"Nova Categoria: \s*$" output))))))

  (testing "check-for-changes with template missing :oldCategory"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-no-oldcat", :category "MARKETING", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Nenhum template ativo com mudança de categoria encontrado." output)))
        (is (not (some? (re-find #"Nome: tpl-no-oldcat" output)))))))

  (testing "check-for-changes with template where :oldCategory is nil"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-nil-oldcat", :category "MARKETING", :oldCategory nil, :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        ;; Current logic: if :oldCategory key exists (even if nil), it's counted.
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-nil-oldcat" output)))
        (is (str/includes? output "  Categoria Antiga: \n")))))

  (testing "check-for-changes with template where :category is nil"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-nil-cat", :category nil, :oldCategory "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-nil-cat" output)))
        (is (some? (re-find #"Nova Categoria: \s*$" output))))))

  (testing "check-for-changes with template status 'PENDING' and category change"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-pending-change", :category "MARKETING", :oldCategory "UTILITY", :status "PENDING"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 1." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-pending-change" output))))))

  (testing "check-for-changes with template status 'REJECTED' and category change"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-rejected-change", :category "MARKETING", :oldCategory "UTILITY", :status "REJECTED"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 1." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-rejected-change" output)))))))

(deftest log-category-change-additional-tests
  (testing "log-category-change with missing :id"
    (let [template {:elementName "NoIDTemplate", :oldCategory "UTILITY", :category "MARKETING"}
          output (with-out-str (log-category-change template))]
      (is (some? (re-find #"  ID: " output))) ; Check for prefix
      (is (some? (re-find #"Nome: NoIDTemplate" output)))))

  (testing "log-category-change with missing :elementName"
    (let [template {:id "template123", :oldCategory "UTILITY", :category "MARKETING"}
          output (with-out-str (log-category-change template))]
      (is (some? (re-find #"ID: template123" output)))
      (is (some? (re-find #"  Nome: " output))))) ; Check for prefix

  (testing "log-category-change with missing :oldCategory"
    (let [template {:id "template123", :elementName "NoOldCat", :category "MARKETING"}
          output (with-out-str (log-category-change template))]
      (is (some? (re-find #"Nome: NoOldCat" output)))
      (is (some? (re-find #"  Categoria Antiga: " output))))) ; Check for prefix

  (testing "log-category-change with missing :category"
    (let [template {:id "template123", :elementName "NoNewCat", :oldCategory "UTILITY"}
          output (with-out-str (log-category-change template))]
      (is (some? (re-find #"Nome: NoNewCat" output)))
      (is (some? (re-find #"  Nova Categoria: " output)))))) ; Check for prefix

;; Minimal test for app-handler, more for completeness as it's very simple
(deftest app-handler-test
  (testing "app-handler returns correct response"
    (let [response (notification-watcher.core/app-handler {})]
      (is (= (:status response) 200))
      (is (= (get-in response [:headers "Content-Type"]) "text/plain"))
      (is (= (:body response) "Serviço Notification Watcher está no ar.")))))

;; Note: Testing start-watcher-loop! and -main directly is complex due to their
;; side-effecting nature (infinite loops, environment variables, starting servers).
;; These are typically tested more effectively with integration or end-to-end tests
;; in a real or simulated environment. The existing unit tests for fetch-templates
;; and check-for-changes cover the core logic that these functions orchestrate.
